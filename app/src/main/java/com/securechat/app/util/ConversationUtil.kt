package com.securechat.app.util

/**
 * 运行时昵称覆盖表：登录后由服务端 /api/contacts 填充（好友真实昵称），
 * 使「修改昵称」对其他成员即时生效。
 *
 * 注意：不再内置任何测试账号名称映射。未知 userId 直接回退为 userId，
 * 避免出现「李开发」之类的错误占位名（开放注册后已无测试账号概念）。
 */
var remoteDisplayNameOverrides: Map<String, String> = emptyMap()

fun teamDisplayName(userId: String): String =
    remoteDisplayNameOverrides[userId] ?: userId

/**
 * 规范化会话 ID：一对一私聊中，双方必须得到相同的会话 ID，
 * 否则发送方以「对方 userId」建会话、接收方以「对方 userId」打开会话会错位。
 * 取两人 userId 排序后拼接，保证对称、确定性。
 */
fun canonicalConversationId(self: String, other: String): String =
    listOf(self, other).sorted().joinToString("_")
