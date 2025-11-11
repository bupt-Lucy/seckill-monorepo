package com.example.authservice.controller;

import com.example.authservice.util.JwtUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private JwtUtil jwtUtil;

    // 模拟登录
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest loginRequest) {
        // 【简化】在真实项目中，这里需要查询数据库并验证密码
        // 我们暂时“假装”验证成功，只要用户名是 "user"
        if ("user".equals(loginRequest.getUsername()) && "123456".equals(loginRequest.getPassword())) {

            // 假设我们从数据库查到了该用户，ID为 "123"
            String mockUserId = "123";

            String token = jwtUtil.generateToken(mockUserId, loginRequest.getUsername());
            return new LoginResponse(token);
        }
        throw new RuntimeException("Invalid credentials");
    }
}

// (你可以把这两个类单独创建为 .java 文件)
@Data
class LoginRequest {
    private String username;
    private String password;
}

@Data
class LoginResponse {
    private String token;
    public LoginResponse(String token) {
        this.token = token;
    }
}