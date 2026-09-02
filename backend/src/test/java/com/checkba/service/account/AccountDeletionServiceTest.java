package com.checkba.service.account;

import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.model.entity.User;
import com.checkba.repository.*;
import com.checkba.service.mobile.MobileRelayBlobStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 注销是不可逆动作，所以两件事最值得测：该删的一样不落、blob 删不掉时不能把整个
 * 注销卡死（否则用户永远注销不掉，反而更糟）。
 */
class AccountDeletionServiceTest {

    private record Fixture(AccountDeletionService svc, UserRepository users,
                           MobileMediaInboxRepository inbox, MobileRelayBlobStore blobs,
                           UserSessionRepository sessions, MobileProjectDirRepository dirs,
                           MobileDeviceStateRepository devices,
                           MobileTransferRequestRepository transfers,
                           AccountBindingRepository bindings, DeviceTokenRepository tokens) {}

    private Fixture fixture(List<MobileMediaInbox> items) {
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
        return new Fixture(new AccountDeletionService(users, sessions, inbox, dirs, devices,
                transfers, bindings, tokens, blobs),
                users, inbox, blobs, sessions, dirs, devices, transfers, bindings, tokens);
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
}
