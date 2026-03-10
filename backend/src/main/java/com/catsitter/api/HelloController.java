package com.catsitter.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
// 加上這行，允許前端的 Port (5173) 來拿資料
@CrossOrigin(origins = "http://localhost:5173") 
public class HelloController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/hello")
    public Map<String, Object> sayHello() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 去問 Docker 裡的 PostgreSQL 現在幾點
            String dbTime = jdbcTemplate.queryForObject("SELECT NOW()", String.class);
            
            response.put("status", "success");
            response.put("message", "哈囉，Will！後端已經成功連上 Docker 資料庫了！");
            response.put("database_time", dbTime);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "資料庫連線失敗：" + e.getMessage());
        }
        
        return response;
    }
}