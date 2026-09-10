// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 名录口连不上 / 回了非 200 / 回了解析不了的东西。
 *
 * <p>它是**故障**，不是"没找到这个人"：上层必须译成"暂时没能核对同事身份，请稍后再试"
 * 让界面红字显示。吞成 {@code found:false} 会在官网抽风时告诉律师"还没有人用这个手机号
 * 注册"——一句彻头彻尾错误、还会引着他去催同事重新注册的话。
 */
public class DirectoryUnavailableException extends RuntimeException {

    public DirectoryUnavailableException(String message) {
        super(message);
    }

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
