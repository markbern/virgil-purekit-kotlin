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

import com.virgilsecurity.purekit.data.InvalidProtobufTypeException
import com.virgilsecurity.purekit.utils.PropertyManager
import com.virgilsecurity.purekit.utils.ProtocolUtils
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * ProtocolNegativeTest class.
 */
class ProtocolNegativeTest {

    private lateinit var protocol: Protocol

    @BeforeEach fun setup() {
        protocol = ProtocolUtils.initProtocol(updateToken = "")
    }

    // HTC-11
    @Test fun enroll_with_empty_pass() {
        runBlocking {
            try {
                protocol.enrollAccount("").await()
            } catch (t: Throwable) {
                assertTrue(t is IllegalArgumentException)
            }
        }
    }

    @Test fun verify_with_empty_pass() {
        runBlocking {
            try {
                protocol.verifyPassword("", ByteArray(0)).await()
            } catch (t: Throwable) {
                assertTrue(t is IllegalArgumentException)
            }
        }
    }

    @Test fun verify_with_empty_record() {
        runBlocking {
            try {
                protocol.verifyPassword(PASSWORD, ByteArray(0)).await()
            } catch (t: Throwable) {
                assertTrue(t is IllegalArgumentException)
            }
        }
    }

    @Test fun verify_with_wrong_record() {
        runBlocking {
            try {
                protocol.verifyPassword(PASSWORD, Random.nextBytes(RANDOM_BYTES_SIZE)).await()
            } catch (t: Throwable) {
                assertTrue(t is InvalidProtobufTypeException)
            }
        }
    }

    @Test fun update_token_empty() {
        var failed = false
        runBlocking {
            try {
                RecordUpdater.updateEnrollmentRecord(Random.nextBytes(RANDOM_BYTES_SIZE),
                                                     "").await()
            } catch (e: IllegalArgumentException) {
                failed = true
            }
        }
        assertTrue(failed)
    }

    @Test fun update_token_empty_record() {
        var failed = false
        runBlocking {
            try {
                RecordUpdater.updateEnrollmentRecord(ByteArray(0), PropertyManager.virgilUpdateTokenNew).await()
            } catch (e: IllegalArgumentException) {
                failed = true
            }
        }
        assertTrue(failed)
    }

    @Test fun update_with_wrong_record() {
        runBlocking {
            try {
                RecordUpdater.updateEnrollmentRecord(Random.nextBytes(RANDOM_BYTES_SIZE),
                                                     PropertyManager.virgilUpdateTokenNew).await()
            } catch (t: Throwable) {
                assertTrue(t is InvalidProtobufTypeException)
            }
        }
    }

    companion object {
        const val RANDOM_BYTES_SIZE = 32

        private const val PASSWORD = "PASSWORD"
    }
}