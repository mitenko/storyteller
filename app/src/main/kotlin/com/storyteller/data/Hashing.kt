package com.storyteller.data

import java.security.MessageDigest

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
