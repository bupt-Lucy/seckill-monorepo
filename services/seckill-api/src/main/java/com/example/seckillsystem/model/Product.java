package com.example.seckillsystem.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

@Entity @Table(name="product")
@Data // Lombok 注解，自动生成 getter, setter 等
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String title;
    private String image;
    private BigDecimal price;
    private Integer stock; // 核心字段：库存
    private Date startTime;
    private Date endTime;
}