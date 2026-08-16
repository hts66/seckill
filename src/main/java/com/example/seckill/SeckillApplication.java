package com.example.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.seckill.mapper")
public class SeckillApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
        System.out.println("========================================");
        System.out.println("  秒杀系统启动成功！");
        System.out.println("  http://localhost:8081");
        System.out.println("========================================");
    }
}
