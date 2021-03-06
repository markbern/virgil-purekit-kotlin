/*
 * Copyright (c) 2015-2019, Virgil Security, Inc.
 *
 * Lead Maintainer: Virgil Security Inc. <support@virgilsecurity.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     (1) Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *
 *     (2) Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *     (3) Neither the name of virgil nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.virgilsecurity.purekit.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.virgilsecurity.purekit.client.HttpClientProtobuf
import com.virgilsecurity.purekit.data.*
import com.virgilsecurity.purekit.protobuf.build.PurekitProtos
import com.virgilsecurity.purekit.utils.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import virgil.crypto.phe.PheCipher
import virgil.crypto.phe.PheClient
import virgil.crypto.phe.PheException
import java.util.concurrent.CompletableFuture

/**
 * Protocol class implements purekit client-server protocol.
 */
class Protocol @JvmOverloads constructor(
        protocolContext: ProtocolContext,
        defaultHttpClient: HttpClientProtobuf? = null
) {

    private val httpClient: HttpClientProtobuf by lazy {
        defaultHttpClient ?: when (protocolContext.appToken.prefix()) {
            PREFIX_PURE_APP_TOKEN -> HttpClientProtobuf(HttpClientProtobuf.DefaultBaseUrls.PASSW0RD.url)
            PREFIX_VIRGIL_APP_TOKEN -> HttpClientProtobuf(HttpClientProtobuf.DefaultBaseUrls.VIRGIL.url)
            else -> throw IllegalArgumentException("App token is wrong. Should be $PREFIX_PURE_APP_TOKEN" +
                                                           "or $PREFIX_VIRGIL_APP_TOKEN." +
                                                           "Current is ${protocolContext.appToken.prefix()}.")
        }
    }

    private val appToken: String = protocolContext.appToken
    private val pheClients: Map<Int, PheClient> = protocolContext.pheClients
    private val currentVersion: Int = protocolContext.version
    private val pheCipher: PheCipher by lazy { PheCipher().apply { setupDefaults() } }

    /**
     * This function requests pseudo-random data from server and uses it to protect [password] and data encryption key.
     *
     * @throws IllegalArgumentException
     * @throws ProtocolException
     * @throws ProtocolHttpException
     * @throws PheException
     */
    @Throws(IllegalArgumentException::class, ProtocolException::class, PheException::class)
    fun enrollAccount(password: String): CompletableFuture<EnrollResult> = GlobalScope.async {
        requires(password.isNotBlank(), "password")

        with(PurekitProtos.EnrollmentRequest.newBuilder().setVersion(currentVersion).build()) {
            with(httpClient.firePost(
                    this,
                    HttpClientProtobuf.AvailableRequests.ENROLL,
                    authToken = appToken,
                    responseParser = PurekitProtos.EnrollmentResponse.parser()

            )) {
                val pheClient = pheClients[this.version]
                        ?: throw NoKeysFoundException("Unable to find keys corresponding to record's version $version.")

                val enrollResult = try {
                    pheClient.enrollAccount(this.response.toByteArray(), password.toByteArray())
                } catch (exception: PheException) {
                    throw InvalidProofException()
                }

                val enrollmentRecord = PurekitProtos.DatabaseRecord
                        .newBuilder()
                        .setVersion(currentVersion)
                        .setRecord(ByteString.copyFrom(enrollResult.enrollmentRecord))
                        .build()
                        .toByteArray()

                EnrollResult(enrollmentRecord, enrollResult.accountKey)
            }
        }
    }.asCompletableFuture()

    /**
     * This function verifies a [password] against [enrollmentRecord] using purekit service.
     *
     * @throws IllegalArgumentException
     * @throws ProtocolException
     * @throws ProtocolHttpException
     * @throws PheException
     * @throws InvalidPasswordException
     * @throws InvalidProtobufTypeException
     */
    @Throws(
            IllegalArgumentException::class,
            ProtocolException::class,
            PheException::class,
            InvalidPasswordException::class,
            InvalidProtobufTypeException::class
    )
    fun verifyPassword(password: String, enrollmentRecord: ByteArray): CompletableFuture<ByteArray> = GlobalScope.async {
        requires(password.isNotBlank(), "password")
        requires(enrollmentRecord.isNotEmpty(), "enrollmentRecord")

        val (version, record) = try {
            with(PurekitProtos.DatabaseRecord.parseFrom(enrollmentRecord)) {
                version to record.toByteArray()
            }
        } catch (e: InvalidProtocolBufferException) {
            throw InvalidProtobufTypeException()
        }

        val pheClient = pheClients[version]
                ?: throw NoKeysFoundException("Unable to find keys corresponding to record's version $version.")

        val request = pheClient.createVerifyPasswordRequest(password.toByteArray(), record)

        val verifyPasswordRequest = PurekitProtos.VerifyPasswordRequest
                .newBuilder()
                .setVersion(version)
                .setRequest(ByteString.copyFrom(request))
                .build()

        with(httpClient.firePost(
                verifyPasswordRequest,
                HttpClientProtobuf.AvailableRequests.VERIFY_PASSWORD,
                authToken = appToken,
                responseParser = PurekitProtos.VerifyPasswordResponse.parser()
        )) {
            val key = try {
                pheClient.checkResponseAndDecrypt(password.toByteArray(), record, response.toByteArray())
            } catch (exception: PheException) {
                throw InvalidProofException()
            }
            if (key.isEmpty()) {
                throw InvalidPasswordException("The password you specified is wrong.")
            }
            key
        }
    }.asCompletableFuture()

    /**
     * This function encrypts provided [data] using [accountKey].
     *
     * @throws IllegalArgumentException
     * @throws PheException
     */
    @Throws(IllegalArgumentException::class, PheException::class)
    fun encrypt(data: ByteArray, accountKey: ByteArray): ByteArray {
        requires(data.isNotEmpty(), "data")
        requires(accountKey.isNotEmpty(), "accountKey")

        return pheCipher.encrypt(data, accountKey)
    }

    /**
     * This function decrypts provided [data] using [accountKey].
     *
     * @throws IllegalArgumentException
     * @throws PheException
     */
    @Throws(IllegalArgumentException::class, PheException::class)
    fun decrypt(data: ByteArray, accountKey: ByteArray): ByteArray {
        requires(data.isNotEmpty(), "data")
        requires(accountKey.isNotEmpty(), "accountKey")

        return pheCipher.decrypt(data, accountKey)
    }
}