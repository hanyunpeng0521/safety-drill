package com.hyp.learn.cf.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hyp.learn.cf.commons.constants.Constant;
import com.hyp.learn.cf.commons.exception.BusinessException;
import com.hyp.learn.cf.commons.exception.code.BaseResponseCode;
import com.hyp.learn.cf.entity.SysRole;
import com.hyp.learn.cf.mapper.SysRoleMapper;
import com.hyp.learn.cf.mapper.SysUserRoleMapper;
import com.hyp.learn.cf.service.*;
import com.hyp.learn.cf.utils.PageUtils;
import com.hyp.learn.cf.config.property.JwtProperties;
import com.hyp.learn.cf.vo.req.RoleAddReqVO;
import com.hyp.learn.cf.vo.req.RolePageReqVO;
import com.hyp.learn.cf.vo.req.RolePermissionOperationReqVO;
import com.hyp.learn.cf.vo.req.RoleUpdateReqVO;
import com.hyp.learn.cf.commons.object.PageVO;
import com.hyp.learn.cf.vo.resp.PermissionRespNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author pingxin
 * @since 2020-03-03
 */
@Service
@Slf4j
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private ISysUserRoleService userRoleService;
    @Autowired
    private ISysRolePermissionService rolePermissionService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private ISysPermissionService permissionService;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public SysRole addRole(RoleAddReqVO vo) {

        SysRole sysRole = new SysRole();
        BeanUtils.copyProperties(vo, sysRole);
        sysRole.setId(UUID.randomUUID().toString());
        sysRole.setCreateTime(LocalDateTime.now());
        int count = sysRoleMapper.insert(sysRole);
        if (count != 1) {
            throw new BusinessException(BaseResponseCode.OPERATION_ERRO);
        }
        if (null != vo.getPermissions() && !vo.getPermissions().isEmpty()) {
            RolePermissionOperationReqVO reqVO = new RolePermissionOperationReqVO();
            reqVO.setRoleId(sysRole.getId());
            reqVO.setPermissionIds(vo.getPermissions());
            rolePermissionService.addRolePermission(reqVO);
        }

        return sysRole;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateRole(RoleUpdateReqVO vo, String accessToken) {
        SysRole sysRole = sysRoleMapper.selectById(vo.getId());
        if (null == sysRole) {
            log.error("传入 的 id:{}不合法", vo.getId());
            throw new BusinessException(BaseResponseCode.DATA_ERROR);
        }
        SysRole update = new SysRole();
        BeanUtils.copyProperties(vo, update);
//        BeanUtils.copyProperties(vo,sysRole);
        update.setUpdateTime(LocalDateTime.now());
        int count = sysRoleMapper.updateById(update);
        if (count != 1) {
            throw new BusinessException(BaseResponseCode.OPERATION_ERRO);
        }
        rolePermissionService.removeByRoleId(sysRole.getId());
        if (null != vo.getPermissions() && !vo.getPermissions().isEmpty()) {
            RolePermissionOperationReqVO reqVO = new RolePermissionOperationReqVO();
            reqVO.setRoleId(sysRole.getId());
            reqVO.setPermissionIds(vo.getPermissions());
            rolePermissionService.addRolePermission(reqVO);

            List<String> userIds = sysUserRoleMapper.getInfoByUserIdByRoleId(vo.getId());

            if (!userIds.isEmpty()) {
                for (String userId : userIds) {
                    redisService.set(Constant.JWT_REFRESH_KEY + userId, userId, jwtProperties.getAccessTokenExpireTime().toMillis(), TimeUnit.MILLISECONDS);
                    //清空权鉴缓存
                    redisService.delete(Constant.IDENTIFY_CACHE_KEY + userId);
                }

            }

        }

    }

    @Override
    public SysRole detailInfo(String id) {
        SysRole sysRole = sysRoleMapper.selectById(id);
        if (sysRole == null) {
            log.error("传入 的 id:{}不合法", id);
            throw new BusinessException(BaseResponseCode.DATA_ERROR);
        }
        List<PermissionRespNode> permissionRespNodes = permissionService.selectAllByTree();
        Set<String> checkList = new HashSet<>(rolePermissionService.getPermissionIdsByRoleId(sysRole.getId()));
        setheckced(permissionRespNodes, checkList);
        sysRole.setPermissionRespNodes(permissionRespNodes);
        return sysRole;
    }


    private void setheckced(List<PermissionRespNode> list, Set<String> checkList) {

        for (PermissionRespNode node : list) {

            if (checkList.contains(node.getId()) && (node.getChildren() == null || node.getChildren().isEmpty())) {
                node.setChecked(true);
            }
            setheckced((List<PermissionRespNode>) node.getChildren(), checkList);

        }
    }


    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deletedRole(String id) {
        SysRole sysRole = new SysRole();
        sysRole.setId(id);
        sysRole.setUpdateTime(LocalDateTime.now());
        sysRole.setDeleted(0);
        int count = sysRoleMapper.updateById(sysRole);
        if (count != 1) {
            throw new BusinessException(BaseResponseCode.OPERATION_ERRO);
        }
        List<String> userIds = sysUserRoleMapper.getInfoByUserIdByRoleId(id);
        rolePermissionService.removeByRoleId(id);
        userRoleService.removeByRoleId(id);

        if (!userIds.isEmpty()) {
            for (String userId : userIds) {
                redisService.set(Constant.JWT_REFRESH_KEY + userId, userId, jwtProperties.getAccessTokenExpireTime().toMillis(), TimeUnit.MILLISECONDS);
                //清空权鉴缓存
                redisService.delete(Constant.IDENTIFY_CACHE_KEY + userId);
            }

        }
    }

    @Override
    public PageVO<SysRole> pageInfo(RolePageReqVO vo) {
        Page<SysRole> page = new Page<>(vo.getPageNum(), vo.getPageSize());

        SysRole sysRole = new SysRole();
        BeanUtils.copyProperties(vo, sysRole);

        Page<SysRole> rolePage = sysRoleMapper.selectPage(page, Wrappers.query(sysRole));

        return PageUtils.getPageVO(rolePage);
    }

    @Override
    public List<SysRole> getRoleInfoByUserId(String userId) {

        List<String> roleIds = userRoleService.getRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return null;
        }
        return sysRoleMapper.selectBatchIds(roleIds);
    }

    @Override
    public List<String> getRoleNames(String userId) {

        List<SysRole> sysRoles = getRoleInfoByUserId(userId);
        if (null == sysRoles || sysRoles.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (SysRole sysRole : sysRoles) {
            list.add(sysRole.getName());
        }
        return list;
    }

    @Override
    public List<SysRole> selectAllRoles() {

        return sysRoleMapper.selectList(Wrappers.query());
    }
}
