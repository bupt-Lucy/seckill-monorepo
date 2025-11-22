package com.example.seckillsystem.repository;

import com.example.seckillsystem.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 【新增】原子化扣减库存的方法
     * 使用 @Modifying 注解来告诉 Spring Data JPA 这是一个“修改”操作
     * 使用 @Query 注解来定义我们的 JPQL 语句
     * WHERE 子句中的 "p.stock > 0" 是关键，它在数据库层面保证了不会超卖
     * @param productId 商品ID
     * @return 返回受影响的行数，如果 > 0 表示更新成功，= 0 表示库存不足或商品不存在
     */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :productId AND p.stock > 0")
    int deductStock(@Param("productId") Long productId);
}