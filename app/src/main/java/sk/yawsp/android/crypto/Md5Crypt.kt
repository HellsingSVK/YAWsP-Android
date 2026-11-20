package sk.yawsp.android.crypto

import java.security.MessageDigest

object Md5Crypt {
    private const val MAGIC = "$1$"
    private const val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun to64(value: Int, count: Int): String {
        var v = value
        val sb = StringBuilder()
        var n = count
        while (n > 0) {
            n--
            sb.append(ITOA64[v and 0x3f])
            v = v ushr 6
        }
        return sb.toString()
    }

    private fun md5(input: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(input)

    fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun md5Hex(input: String): String =
        md5(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun unixMd5Crypt(password: String, saltRaw: String): String {
        val pw = password.toByteArray(Charsets.UTF_8)
        val cleanSalt = saltRaw.removePrefix(MAGIC).substringBefore('$').take(8)
        val salt = cleanSalt.toByteArray(Charsets.UTF_8)
        val magicBytes = MAGIC.toByteArray(Charsets.UTF_8)

        val ctx = mutableListOf<Byte>()
        ctx.addAll(pw.toList())
        ctx.addAll(magicBytes.toList())
        ctx.addAll(salt.toList())

        var finalDigest = md5(pw + salt + pw)
        var pl = pw.size
        while (pl > 0) {
            ctx.addAll(finalDigest.take(minOf(pl, 16)))
            pl -= 16
        }

        var i = pw.size
        while (i != 0) {
            if ((i and 1) != 0) {
                ctx.add(0)
            } else {
                ctx.add(pw[0])
            }
            i = i ushr 1
        }

        finalDigest = md5(ctx.toByteArray())

        for (round in 0 until 1000) {
            val ctx1 = mutableListOf<Byte>()
            if ((round and 1) != 0) {
                ctx1.addAll(pw.toList())
            } else {
                ctx1.addAll(finalDigest.take(16))
            }
            if (round % 3 != 0) {
                ctx1.addAll(salt.toList())
            }
            if (round % 7 != 0) {
                ctx1.addAll(pw.toList())
            }
            if ((round and 1) != 0) {
                ctx1.addAll(finalDigest.take(16))
            } else {
                ctx1.addAll(pw.toList())
            }
            finalDigest = md5(ctx1.toByteArray())
        }

        fun uByte(b: Byte): Int = b.toInt() and 0xff

        val passwd = StringBuilder()
        passwd.append(to64((uByte(finalDigest[0]) shl 16) or (uByte(finalDigest[6]) shl 8) or uByte(finalDigest[12]), 4))
        passwd.append(to64((uByte(finalDigest[1]) shl 16) or (uByte(finalDigest[7]) shl 8) or uByte(finalDigest[13]), 4))
        passwd.append(to64((uByte(finalDigest[2]) shl 16) or (uByte(finalDigest[8]) shl 8) or uByte(finalDigest[14]), 4))
        passwd.append(to64((uByte(finalDigest[3]) shl 16) or (uByte(finalDigest[9]) shl 8) or uByte(finalDigest[15]), 4))
        passwd.append(to64((uByte(finalDigest[4]) shl 16) or (uByte(finalDigest[10]) shl 8) or uByte(finalDigest[5]), 4))
        passwd.append(to64(uByte(finalDigest[11]), 2))

        return "$MAGIC$cleanSalt$$passwd"
    }
}
