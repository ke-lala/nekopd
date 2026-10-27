package org.purplei2p.i2pd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class AddressBookRepositoryTest {
    @Test
    fun sha256DigestUsesUnpaddedLowercaseBase32() {
        val digest = MessageDigest.getInstance("SHA-256").digest(byteArrayOf())
        assertEquals(
            "4oymiquy7qobjgx36tejs35zeqt24qpemsnzgtfeswmrw6csxbkq",
            AddressBookRepository.encodeBase32(digest),
        )
    }
}
