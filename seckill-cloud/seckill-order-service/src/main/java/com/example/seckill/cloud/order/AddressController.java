package com.example.seckill.cloud.order;

import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
public class AddressController {
    private final JdbcClient jdbc;

    public AddressController(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record AddressView(Long id, String receiverName, String receiverPhone, String province,
                              String city, String district, String detail, Boolean isDefault,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record SaveAddress(
            @NotBlank @Size(max = 50) String receiverName,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入有效的手机号") String receiverPhone,
            @NotBlank @Size(max = 50) String province,
            @NotBlank @Size(max = 50) String city,
            @NotBlank @Size(max = 50) String district,
            @NotBlank @Size(max = 255) String detail,
            Boolean isDefault) {}

    @GetMapping("/api/addresses")
    ApiResponse<List<AddressView>> list(HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        return ApiResponse.ok(jdbc.sql("SELECT id,receiver_name,receiver_phone,province,city,district,detail," +
                        "is_default,created_at,updated_at FROM user_addresses WHERE user_id=:userId " +
                        "ORDER BY is_default DESC,id DESC")
                .param("userId", userId).query(AddressView.class).list());
    }

    @PostMapping("/api/addresses")
    @Transactional
    ApiResponse<Void> create(@Valid @RequestBody SaveAddress address, HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        boolean makeDefault = Boolean.TRUE.equals(address.isDefault()) || count(userId) == 0;
        if (makeDefault) clearDefault(userId);
        jdbc.sql("INSERT INTO user_addresses(user_id,receiver_name,receiver_phone,province,city,district,detail,is_default) " +
                        "VALUES(:userId,:name,:phone,:province,:city,:district,:detail,:isDefault)")
                .param("userId", userId).param("name", address.receiverName().trim())
                .param("phone", address.receiverPhone().trim()).param("province", address.province().trim())
                .param("city", address.city().trim()).param("district", address.district().trim())
                .param("detail", address.detail().trim()).param("isDefault", makeDefault).update();
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/addresses/{id}")
    @Transactional
    ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody SaveAddress address,
                             HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        requireOwned(id, userId);
        if (Boolean.TRUE.equals(address.isDefault())) clearDefault(userId);
        jdbc.sql("UPDATE user_addresses SET receiver_name=:name,receiver_phone=:phone,province=:province," +
                        "city=:city,district=:district,detail=:detail,is_default=:isDefault WHERE id=:id AND user_id=:userId")
                .param("name", address.receiverName().trim()).param("phone", address.receiverPhone().trim())
                .param("province", address.province().trim()).param("city", address.city().trim())
                .param("district", address.district().trim()).param("detail", address.detail().trim())
                .param("isDefault", Boolean.TRUE.equals(address.isDefault()))
                .param("id", id).param("userId", userId).update();
        ensureDefault(userId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/addresses/{id}/default")
    @Transactional
    ApiResponse<Void> setDefault(@PathVariable Long id, HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        requireOwned(id, userId);
        clearDefault(userId);
        jdbc.sql("UPDATE user_addresses SET is_default=1 WHERE id=:id AND user_id=:userId")
                .param("id", id).param("userId", userId).update();
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/addresses/{id}")
    @Transactional
    ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        requireOwned(id, userId);
        jdbc.sql("DELETE FROM user_addresses WHERE id=:id AND user_id=:userId")
                .param("id", id).param("userId", userId).update();
        ensureDefault(userId);
        return ApiResponse.ok(null);
    }

    private int count(Long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM user_addresses WHERE user_id=:userId")
                .param("userId", userId).query(Integer.class).single();
    }

    private void requireOwned(Long id, Long userId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM user_addresses WHERE id=:id AND user_id=:userId")
                .param("id", id).param("userId", userId).query(Integer.class).single();
        if (count == 0) throw new IllegalArgumentException("收货地址不存在");
    }

    private void clearDefault(Long userId) {
        jdbc.sql("UPDATE user_addresses SET is_default=0 WHERE user_id=:userId")
                .param("userId", userId).update();
    }

    private void ensureDefault(Long userId) {
        Integer defaults = jdbc.sql("SELECT COUNT(*) FROM user_addresses WHERE user_id=:userId AND is_default=1")
                .param("userId", userId).query(Integer.class).single();
        if (defaults == 0) {
            jdbc.sql("UPDATE user_addresses SET is_default=1 WHERE id=(SELECT id FROM " +
                            "(SELECT id FROM user_addresses WHERE user_id=:userId ORDER BY id DESC LIMIT 1) candidate)")
                    .param("userId", userId).update();
        }
    }
}
