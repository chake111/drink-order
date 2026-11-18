package com.drink.server.controller;

import com.drink.server.common.PageResult;
import com.drink.server.common.Result;
import com.drink.server.dto.DrinkFormDTO;
import com.drink.server.dto.DrinkPageQuery;
import com.drink.server.entity.Drink;
import com.drink.server.service.DrinkService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 饮品管理控制器（管理端）
 */
@RestController
@RequestMapping("/api/drink")
public class DrinkController {

    private final DrinkService drinkService;

    public DrinkController(DrinkService drinkService) {
        this.drinkService = drinkService;
    }

    @GetMapping("/page")
    public Result<PageResult<Drink>> page(DrinkPageQuery query) {
        return Result.success(drinkService.pageQuery(query));
    }

    @GetMapping("/{id}")
    public Result<Drink> getById(@PathVariable Long id) {
        return Result.success(drinkService.getById(id));
    }

    @PostMapping
    public Result<Void> add(@ModelAttribute DrinkFormDTO form) {
        drinkService.add(form.toEntity(), form.getFlavors());
        return Result.success("新增成功", null);
    }

    @PutMapping
    public Result<Void> update(@ModelAttribute DrinkFormDTO form) {
        drinkService.update(form.toEntity(), form.getFlavors());
        return Result.success("修改成功", null);
    }

    @PostMapping("/status/{status}")
    public Result<Void> updateStatus(@PathVariable Integer status, @RequestParam String ids) {
        List<Long> idList = parseIds(ids);
        drinkService.updateStatus(idList, status);
        return Result.success("操作成功", null);
    }

    @DeleteMapping
    public Result<Void> delete(@RequestParam String ids) {
        List<Long> idList = parseIds(ids);
        drinkService.delete(idList);
        return Result.success("删除成功", null);
    }

    /**
     * 解析逗号分隔的ID字符串
     */
    private List<Long> parseIds(String ids) {
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }
}
