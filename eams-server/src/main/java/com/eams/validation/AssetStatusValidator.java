package com.eams.validation;

import com.eams.constant.AssetStatusConstant;
import com.eams.constant.MessageConstant;
import com.eams.exception.BaseException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 资产状态流转校验器
 *
 * <p>维护所有允许的状态流转规则，所有涉及资产状态变更的操作统一走此校验，
 * 避免流转规则散落在各个 Service 中。</p>
 *
 * <pre>
 * 在库(1)   → 已领用、维修中、报废
 * 已领用(2) → 在库、维修中
 * 维修中(3) → 在库
 * 报废(4)   → 无（终态）
 * </pre>
 */
public class AssetStatusValidator {

    private static final Map<Integer, Set<Integer>> TRANSITION_RULES = new HashMap<>();

    static {
        TRANSITION_RULES.put(AssetStatusConstant.IN_STOCK, Set.of(
                AssetStatusConstant.USED,
                AssetStatusConstant.REPAIRING,
                AssetStatusConstant.SCRAPPED
        ));
        TRANSITION_RULES.put(AssetStatusConstant.USED, Set.of(
                AssetStatusConstant.IN_STOCK,
                AssetStatusConstant.REPAIRING
        ));
        TRANSITION_RULES.put(AssetStatusConstant.REPAIRING, Set.of(
                AssetStatusConstant.IN_STOCK
        ));
        TRANSITION_RULES.put(AssetStatusConstant.SCRAPPED, Collections.emptySet());
    }

    /**
     * 校验资产状态流转是否合法
     *
     * @param currentStatus 当前状态
     * @param targetStatus  目标状态
     * @throws BaseException 如果流转不合法
     */
    public static void validate(Integer currentStatus, Integer targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            throw new BaseException(MessageConstant.ASSET_STATUS_TRANSITION_INVALID);
        }
        Set<Integer> allowed = TRANSITION_RULES.get(currentStatus);
        if (allowed == null || !allowed.contains(targetStatus)) {
            throw new BaseException(MessageConstant.ASSET_STATUS_TRANSITION_INVALID);
        }
    }

    /**
     * 获取某个状态允许流转到的目标状态集合
     */
    public static Set<Integer> getAllowedTargets(Integer status) {
        return TRANSITION_RULES.getOrDefault(status, Collections.emptySet());
    }
}
