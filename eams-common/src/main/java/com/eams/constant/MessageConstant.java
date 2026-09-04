package com.eams.constant;

/**
 * 信息提示常量类
 */
public class MessageConstant {

    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String ACCOUNT_LOCKED = "账号被锁定";
    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String ALREADY_EXISTS = "已存在";
    public static final String USER_NOT_LOGIN = "用户未登录";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String PASSWORD_EDIT_FAILED = "密码修改失败";
    public static final String OLD_PASSWORD_ERROR = "原密码错误";
    public static final String NOT_ADMIN = "无权限操作，仅管理员可访问";
    public static final String RATE_LIMIT_EXCEEDED = "操作过于频繁，请稍后再试";

    public static final String CATEGORY_BE_RELATED_BY_ASSET = "当前分类下存在资产，不能删除";
    public static final String ASSET_NOT_FOUND = "资产不存在";
    public static final String ASSET_STATUS_ERROR = "资产状态不允许该操作";
    public static final String ASSET_CANNOT_DELETE = "资产已领用/维修中/报废，不能删除";

    public static final String APPLICATION_NOT_FOUND = "申请单不存在";
    public static final String APPLICATION_STATUS_ERROR = "申请单状态不允许该操作";
    public static final String APPLICATION_ASSET_REQUIRED = "申请资产不能为空";

    public static final String REPAIR_NOT_FOUND = "报修记录不存在";
    public static final String PHONE_NOT_FOUND = "手机号未注册";
    public static final String ASSET_STATUS_TRANSITION_INVALID = "资产状态流转不合法";
}
