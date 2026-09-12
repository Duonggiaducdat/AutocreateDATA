package com.fpt.autodata.generator.impl.auth;

import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared TGT (Ticket Granting Ticket) cookie cache cho tất cả auth generators.
 *
 * CAS TGT là session cookie được set sau khi login thành công lần đầu.
 * Khi còn hợp lệ, GET /id/login?service=... trả 302+ticket ngay — không cần
 * POST credentials lại. Tất cả generators dùng chung cache này theo username.
 */
@Component
public class CasTgtCache {

    private final ConcurrentHashMap<String, CookieManager> store = new ConcurrentHashMap<>();

    /** Lấy hoặc tạo mới CookieManager cho username. */
    public CookieManager getOrCreate(String username) {
        return store.computeIfAbsent(username,
            k -> new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    /** Xóa TGT khi login thất bại (tránh dùng lại session lỗi). */
    public void invalidate(String username) {
        store.remove(username);
    }

    public boolean hasCached(String username) {
        return store.containsKey(username);
    }
}
