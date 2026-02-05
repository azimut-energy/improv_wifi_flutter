package com.example.improv_wifi

enum class RpcCommand(val value: UByte) {
    SEND_WIFI(1.toUByte()),
    IDENTIFY(2.toUByte())
}
