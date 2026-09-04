package com.eams.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.eams.constant.JwtClaimsConstant;
import com.eams.constant.MessageConstant;
import com.eams.constant.PasswordConstant;
import com.eams.constant.RedisConstant;
import com.eams.constant.RoleConstant;
import com.eams.constant.StatusConstant;
import com.eams.context.BaseContext;
import com.eams.dto.EmployeeDTO;
import com.eams.dto.EmployeeLoginDTO;
import com.eams.dto.EmployeePageQueryDTO;
import com.eams.dto.PasswordEditDTO;
import com.eams.entity.Employee;
import com.eams.exception.AccountLockedException;
import com.eams.exception.AccountNotFoundException;
import com.eams.exception.PasswordErrorException;
import com.eams.mapper.EmployeeMapper;
import com.eams.properties.JwtProperties;
import com.eams.result.PageResult;
import com.eams.service.EmployeeService;
import com.eams.utils.JwtUtil;
import com.eams.vo.EmployeeLoginVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** Local token cache (Redis fallback) */
    private final Map<Long, String> localTokenMap = new ConcurrentHashMap<>();

    /**
     * 员工登录：支持用户名（管理端）或手机号（H5 端）+ 密码
     * 登录成功后生成 JWT，并将 token 写入 Redis（7 天过期）
     */
    @Override
    @Transactional
    public EmployeeLoginVO login(EmployeeLoginDTO employeeLoginDTO) {
        String password = employeeLoginDTO.getPassword();
        Employee employee;

        if (employeeLoginDTO.getUsername() != null && !employeeLoginDTO.getUsername().isEmpty()) {
            employee = employeeMapper.getByUsername(employeeLoginDTO.getUsername());
        } else if (employeeLoginDTO.getPhone() != null && !employeeLoginDTO.getPhone().isEmpty()) {
            employee = employeeMapper.getByPhone(employeeLoginDTO.getPhone());
        } else {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        // 密码比对（MD5）
        password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!password.equals(employee.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        if (employee.getStatus() == StatusConstant.DISABLE) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        // 生成 JWT（claims 中携带角色/部门，便于拦截器做权限校验）
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, employee.getId());
        claims.put(JwtClaimsConstant.ROLE, employee.getRole());
        claims.put(JwtClaimsConstant.DEPARTMENT, employee.getDepartment());
        claims.put(JwtClaimsConstant.NAME, employee.getName());
        claims.put(JwtClaimsConstant.USERNAME, employee.getUsername());
        claims.put(JwtClaimsConstant.PHONE, employee.getPhone());
        String token = JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);

        // 登录态写入 Redis，7 天过期
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstant.LOGIN_TOKEN_KEY + employee.getId(),
                    token,
                    RedisConstant.LOGIN_TOKEN_TTL,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis unavailable, using local cache: {}", e.getMessage());
            localTokenMap.put(employee.getId(), token);
        }

        return EmployeeLoginVO.builder()
                .id(employee.getId())
                .userName(employee.getUsername())
                .name(employee.getName())
                .phone(employee.getPhone())
                .role(employee.getRole())
                .department(employee.getDepartment())
                .token(token)
                .build();
    }

    /**
     * 退出登录：删除 Redis 中的 token
     */
    @Override
    public void logout() {
        Long currentId = BaseContext.getCurrentId();
        if (currentId != null) {
            try {
                stringRedisTemplate.delete(RedisConstant.LOGIN_TOKEN_KEY + currentId);
            } catch (Exception e) {
                log.warn("Redis unavailable, clearing local cache");
            }
            localTokenMap.remove(currentId);
            log.info("员工退出登录, empId={}", currentId);
        }
        BaseContext.removeCurrentId();
    }

    /**
     * 新增员工
     */
    @Override
    public void add(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);

        employee.setPassword(DigestUtils.md5DigestAsHex(PasswordConstant.DEFAULT_PASSWORD.getBytes()));
        employee.setStatus(StatusConstant.ENABLE);
        if (employee.getRole() == null) {
            employee.setRole(RoleConstant.EMPLOYEE);
        }
        employeeMapper.insert(employee);
    }

    /**
     * 分页查询
     */
    @Override
    public PageResult page(EmployeePageQueryDTO employeePageQueryDTO) {
        PageHelper.startPage(employeePageQueryDTO.getPage(), employeePageQueryDTO.getPageSize());
        Page<Employee> employeePage = employeeMapper.pageQuery(employeePageQueryDTO);
        long total = employeePage.getTotal();
        List<Employee> records = employeePage.getResult();
        return new PageResult(total, records);
    }

    /**
     * 启用/禁用员工状态
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        Employee employee = Employee.builder()
                .status(status)
                .id(id)
                .build();
        employeeMapper.update(employee);
    }

    /**
     * 根据 ID 查询员工信息（密码脱敏）
     */
    @Override
    public Employee queryById(Long id) {
        Employee employee = employeeMapper.getById(id);
        if (employee != null) {
            employee.setPassword("****");
        }
        return employee;
    }

    /**
     * 编辑员工信息
     */
    @Override
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);
        employeeMapper.update(employee);
    }

    /**
     * 修改密码
     */
    @Override
    public void editPassword(PasswordEditDTO passwordEditDTO) {
        Long empId = passwordEditDTO.getEmpId() != null ? passwordEditDTO.getEmpId() : BaseContext.getCurrentId();
        Employee employee = employeeMapper.getById(empId);
        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        String oldPassword = DigestUtils.md5DigestAsHex(passwordEditDTO.getOldPassword().getBytes());
        if (!oldPassword.equals(employee.getPassword())) {
            throw new PasswordErrorException(MessageConstant.OLD_PASSWORD_ERROR);
        }

        Employee update = Employee.builder()
                .id(empId)
                .password(DigestUtils.md5DigestAsHex(passwordEditDTO.getNewPassword().getBytes()))
                .build();
        employeeMapper.update(update);
    }

    public String getLocalToken(Long empId) {
        return localTokenMap.get(empId);
    }

    public Map<Long, String> getLocalTokenMap() {
        return localTokenMap;
    }
}
