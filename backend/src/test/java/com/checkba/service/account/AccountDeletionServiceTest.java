// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.model.entity.User;
import com.checkba.repository.*;
import com.checkba.service.mobile.MobileBillingClient;
import com.checkba.service.mobile.MobileBillingFailureException;
import com.checkba.service.mobile.MobileBillingKind;
import com.checkba.service.mobile.MobileRelayBlobStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 注销是不可逆动作，所以三件事最值得测：该删的一样不落、blob 删不掉时不能把整个
 * 注销卡死（否则用户永远注销不掉，反而更糟）、以及官网侧的传导（dev-board#434）——
 * 传导失败时<b>本地一行都不能删</b>，否则官网那行含明文手机号的账户就成了没人认领的孤儿。
 */
class AccountDeletionServiceTest {

    private record Fixture(AccountDeletionService svc, UserRepository users,
                           MobileMediaInboxRepository inbox, MobileRelayBlobStore blobs,
                           UserSessionRepository sessions, MobileProjectDirRepository dirs,
                           MobileDeviceStateRepository devices,
                           MobileTransferRequestRepository transfers,
                           AccountBindingRepository bindings, DeviceTokenRepository tokens,
                           MobileBillingClient billing) {}

    private Fixture fixture(List<MobileMediaInbox> items) {
        return fixture(items, null);
    }

    /** @param externalAccountId 非 null 时给这个用户放一行 account_binding（= 官网侧有账户） */
    private Fixture fixture(List<MobileMediaInbox> items, String externalAccountId) {
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(new User()));
        MobileMediaInboxRepository inbox = mock(MobileMediaInboxRepository.class);
        when(inbox.findByUserId(7L)).thenReturn(items);
        MobileRelayBlobStore blobs = mock(MobileRelayBlobStore.class);
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        MobileProjectDirRepository dirs = mock(MobileProjectDirRepository.class);
        MobileDeviceStateRepository devices = mock(MobileDeviceStateRepository.class);
        MobileTransferRequestRepository transfers = mock(MobileTransferRequestRepository.class);
        AccountBindingRepository bindings = mock(AccountBindingRepository.class);
        DeviceTokenRepository tokens = mock(DeviceTokenRepository.class);
        if (externalAccountId != null) {
            AccountBinding row = new AccountBinding();
            row.setUserId(7L);
            row.setExternalAccountId(externalAccountId);
            when(bindings.findByUserId(7L)).thenReturn(Optional.of(row));
        } else {
            when(bindings.findByUserId(7L)).thenReturn(Optional.empty());
        }
        MobileBillingClient billing = mock(MobileBillingClient.class);
        return new Fixture(new AccountDeletionService(users, sessions, inbox, dirs, devices,
                transfers, bindings, tokens, blobs, billing),
                users, inbox, blobs, sessions, dirs, devices, transfers, bindings, tokens, billing);
    }

    private static MobileMediaInbox item(String path) {
        MobileMediaInbox m = new MobileMediaInbox();
        m.setStoragePath(path);
        return m;
    }

    @Test
    @DisplayName("该删的一样不落：blob、四张手机端表、会话、绑定、令牌、账号本身")
    void deletesEverythingOwnedByTheUser() {
        Fixture f = fixture(List.of(item("relay/a.bin"), item("relay/b.bin")));

        f.svc().deleteAccount(7L);

        verify(f.blobs()).deleteQuietly("relay/a.bin");
        verify(f.blobs()).deleteQuietly("relay/b.bin");
        verify(f.inbox()).deleteByUserId(7L);
        verify(f.dirs()).deleteByUserId(7L);
        verify(f.devices()).deleteByUserId(7L);
        verify(f.transfers()).deleteByUserId(7L);
        verify(f.sessions()).deleteByUserId(7L);
        verify(f.bindings()).deleteByUserId(7L);
        verify(f.tokens()).deleteByUserId(7L);
        verify(f.users()).deleteById(7L);
    }

    @Test
    @DisplayName("已投递的件没有 blob，不该去删空路径")
    void skipsDeliveredItemsWithoutBlob() {
        Fixture f = fixture(List.of(item(null), item("relay/c.bin")));
        f.svc().deleteAccount(7L);
        verify(f.blobs(), times(1)).deleteQuietly(any());
        verify(f.blobs()).deleteQuietly("relay/c.bin");
    }

    @Test
    @DisplayName("blob 删不掉也要把账号删干净——否则用户永远注销不掉")
    void blobFailureDoesNotBlockDeletion() {
        Fixture f = fixture(List.of(item("relay/stuck.bin")));
        doThrow(new RuntimeException("对象存储抽风")).when(f.blobs()).deleteQuietly(any());

        // deleteQuietly 的契约就是不抛；真抛了也不该让注销半途而废
        assertThrows(RuntimeException.class, () -> f.svc().deleteAccount(7L));
        verify(f.users(), never()).deleteById(any());
    }

    @Test
    @DisplayName("查无此人：回业务错，不是 500")
    void unknownUserIsRejectedCleanly() {
        Fixture f = fixture(List.of());
        when(f.users().findById(7L)).thenReturn(Optional.empty());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.svc().deleteAccount(7L));
        assertEquals("账号不存在或已注销", e.getMessage());
        verify(f.users(), never()).deleteById(any());
    }

    // ==================== 官网侧传导（dev-board#434） ====================

    @Test
    @DisplayName("有绑定：先把删除传导到官网，官网删成功才删本地")
    void propagatesToUnifiedAccountBeforeDeletingLocally() {
        Fixture f = fixture(List.of(), "acct-9");
        when(f.billing().deleteAccount("acct-9"))
                .thenReturn(new MobileBillingClient.DeleteAccountResult(true, null, null));

        f.svc().deleteAccount(7L);

        InOrder order = inOrder(f.billing(), f.users());
        order.verify(f.billing()).deleteAccount("acct-9");
        order.verify(f.users()).deleteById(7L);
    }

    @Test
    @DisplayName("官网明确拒绝删除：本地一行都不删，且把官网给的原因原样告诉用户")
    void blockedUpstreamDeletionAbortsLocalDeletion() {
        Fixture f = fixture(List.of(item("relay/x.bin")), "acct-9");
        when(f.billing().deleteAccount("acct-9")).thenReturn(
                new MobileBillingClient.DeleteAccountResult(false, "refundable_balance",
                        "账上还有可退余额，请先申请退款"));

        MobileBillingFailureException e = assertThrows(MobileBillingFailureException.class,
                () -> f.svc().deleteAccount(7L));

        assertEquals(MobileBillingKind.REJECTED, e.getKind());
        assertEquals("账上还有可退余额，请先申请退款", e.getMessage());
        verify(f.users(), never()).deleteById(any());
        verify(f.bindings(), never()).deleteByUserId(any());
        verify(f.blobs(), never()).deleteQuietly(any());
    }

    @Test
    @DisplayName("官网不可达：同样中止——宁可注销失败一次，也不留下官网侧的孤儿账户")
    void unreachableUpstreamAbortsLocalDeletion() {
        Fixture f = fixture(List.of(), "acct-9");
        when(f.billing().deleteAccount("acct-9")).thenThrow(
                new MobileBillingClient.MobileBillingException(
                        MobileBillingKind.UNAVAILABLE, "账户服务暂不可用，请稍后再试"));

        MobileBillingFailureException e = assertThrows(MobileBillingFailureException.class,
                () -> f.svc().deleteAccount(7L));

        assertEquals(MobileBillingKind.UNAVAILABLE, e.getKind());
        verify(f.users(), never()).deleteById(any());
        verify(f.bindings(), never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("没有绑定（含整台服务器没配统一账户）：一次上游请求都不发，照常删")
    void unboundUserIsDeletedWithoutUpstreamCall() {
        Fixture f = fixture(List.of());

        f.svc().deleteAccount(7L);

        verifyNoInteractions(f.billing());
        verify(f.users()).deleteById(7L);
    }
}
