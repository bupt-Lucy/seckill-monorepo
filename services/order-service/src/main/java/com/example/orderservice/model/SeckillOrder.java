package com.example.orderservice.model;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

@Entity
@Data
public class SeckillOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long productId;
    private BigDecimal orderPrice;
    private Date createTime;
}