package com.eams.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.eams.constant.MessageConstant;
import com.eams.context.BaseContext;
import com.eams.dto.AssetDTO;
import com.eams.dto.AssetPageQueryDTO;
import com.eams.entity.Asset;
import com.eams.entity.AssetCategory;
import com.eams.exception.BaseException;
import com.eams.mapper.AssetCategoryMapper;
import com.eams.mapper.AssetMapper;
import com.eams.result.PageResult;
import com.eams.service.AssetService;
import com.eams.validation.AssetStatusValidator;
import com.eams.vo.AssetVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class AssetServiceImpl implements AssetService {

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetCategoryMapper assetCategoryMapper;

    /**
     * 新增资产：编号为空时自动生成，状态默认在库
     */
    @Override
    public void add(AssetDTO assetDTO) {
        Asset asset = new Asset();
        BeanUtils.copyProperties(assetDTO, asset);

        if (asset.getCode() == null || asset.getCode().isEmpty()) {
            asset.setCode("ZC" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH")));
        }
        if (asset.getStatus() == null) {
            asset.setStatus(Asset.IN_STOCK);
        }
        assetMapper.insert(asset);
    }

    /**
     * 资产分页查询
     */
    @Override
    public PageResult pageQuery(AssetPageQueryDTO assetPageQueryDTO) {
        PageHelper.startPage(assetPageQueryDTO.getPage(), assetPageQueryDTO.getPageSize());
        Page<AssetVO> page = assetMapper.pageQuery(assetPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 根据 id 查询资产详情（含分类名称）
     */
    @Override
    public AssetVO getById(Long id) {
        Asset asset = assetMapper.getById(id);
        if (asset == null) {
            throw new BaseException(MessageConstant.ASSET_NOT_FOUND);
        }
        AssetVO assetVO = new AssetVO();
        BeanUtils.copyProperties(asset, assetVO);
        if (asset.getCategoryId() != null) {
            AssetCategory category = assetCategoryMapper.getById(asset.getCategoryId());
            if (category != null) {
                assetVO.setCategoryName(category.getName());
            }
        }
        return assetVO;
    }

    /**
     * 修改资产信息
     */
    @Override
    public void update(AssetDTO assetDTO) {
        Asset asset = new Asset();
        BeanUtils.copyProperties(assetDTO, asset);
        assetMapper.update(asset);
    }

    /**
     * 根据 id 删除资产：仅允许删除在库资产
     */
    @Override
    public void deleteById(Long id) {
        Asset asset = assetMapper.getById(id);
        if (asset == null) {
            throw new BaseException(MessageConstant.ASSET_NOT_FOUND);
        }
        if (asset.getStatus() != null && asset.getStatus() != Asset.IN_STOCK) {
            throw new BaseException(MessageConstant.ASSET_CANNOT_DELETE);
        }
        assetMapper.deleteById(id);
    }

    /**
     * 更新资产状态（管理员手动调整：在库/报废等）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        Asset current = assetMapper.getById(id);
        if (current != null) {
            AssetStatusValidator.validate(current.getStatus(), status);
        }
        Asset asset = Asset.builder()
                .id(id)
                .status(status)
                .updateTime(LocalDateTime.now())
                .updateUser(BaseContext.getCurrentId())
                .build();
        assetMapper.updateStatus(asset);
    }
}
