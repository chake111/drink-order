package com.drink.server.dto;

import com.drink.server.entity.Drink;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 饮品新增/修改表单。
 *
 * <p>管理端以 {@code multipart/form-data} 提交，字段与原 {@code @RequestParam}
 * 一一对应，接口契约不变；口味规格保持 JSON 字符串，由服务层解析。
 */
@Data
public class DrinkFormDTO {

    private Long id;
    private String name;
    private Long categoryId;
    private BigDecimal price;
    private String image;
    private String description;

    /** 口味规格 JSON 字符串 */
    private String flavors;

    public Drink toEntity() {
        Drink drink = new Drink();
        drink.setId(id);
        drink.setName(name);
        drink.setCategoryId(categoryId);
        drink.setPrice(price);
        drink.setImage(image);
        drink.setDescription(description);
        return drink;
    }
}