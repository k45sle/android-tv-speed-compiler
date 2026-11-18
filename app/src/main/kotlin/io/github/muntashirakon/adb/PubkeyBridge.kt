package io.github.muntashirakon.adb

import java.security.interfaces.RSAPublicKey

/**
 * [key] exactly as libadb sends it to adbd (android_pubkey_encode, base64), without the trailing device name. The TV
 * stores this text when it saves Always allow. It lives in libadb's package because AndroidPubkey is package-private.
 */
fun adbPublicKeyBase64(key: RSAPublicKey): String =
    String(AndroidPubkey.encodeWithName(key, "x"), Charsets.UTF_8).substringBefore(' ')
