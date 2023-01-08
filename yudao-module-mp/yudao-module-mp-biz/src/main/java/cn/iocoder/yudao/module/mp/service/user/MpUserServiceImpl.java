package cn.iocoder.yudao.module.mp.service.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.module.mp.controller.admin.user.vo.MpUserPageReqVO;
import cn.iocoder.yudao.module.mp.convert.user.MpUserConvert;
import cn.iocoder.yudao.module.mp.dal.dataobject.account.MpAccountDO;
import cn.iocoder.yudao.module.mp.dal.dataobject.user.MpUserDO;
import cn.iocoder.yudao.module.mp.dal.mysql.user.MpUserMapper;
import cn.iocoder.yudao.module.mp.framework.mp.core.MpServiceFactory;
import cn.iocoder.yudao.module.mp.service.account.MpAccountService;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
import me.chanjar.weixin.mp.bean.result.WxMpUserList;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 微信公众号粉丝 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
@Slf4j
public class MpUserServiceImpl implements MpUserService {

    @Resource
    @Lazy // 延迟加载，解决循环依赖的问题
    private MpAccountService mpAccountService;

    @Resource
    @Lazy // 延迟加载，解决循环依赖的问题
    private MpServiceFactory mpServiceFactory;

    @Resource
    private MpUserMapper mpUserMapper;

    @Override
    public MpUserDO getUser(Long id) {
        return mpUserMapper.selectById(id);
    }

    @Override
    public MpUserDO getUser(String appId, String openId) {
        return mpUserMapper.selectByAppIdAndOpenid(appId, openId);
    }

    @Override
    public List<MpUserDO> getUserList(Collection<Long> ids) {
        return mpUserMapper.selectBatchIds(ids);
    }

    @Override
    public PageResult<MpUserDO> getUserPage(MpUserPageReqVO pageReqVO) {
        return mpUserMapper.selectPage(pageReqVO);
    }

    @Override
    public MpUserDO saveUser(String appId, WxMpUser wxMpUser) {
        // 构建保存的 MpUserDO 对象
        MpAccountDO account = mpAccountService.getAccountFromCache(appId);
        MpUserDO user = MpUserConvert.INSTANCE.convert(account, wxMpUser);

        // 根据情况，插入或更新
        MpUserDO dbUser = mpUserMapper.selectByAppIdAndOpenid(appId, wxMpUser.getOpenId());
        if (dbUser == null) {
            mpUserMapper.insert(user);
        } else {
            user.setId(dbUser.getId());
            mpUserMapper.updateById(user);
        }
        return user;
    }

    @Override
    @Async
    public void syncUser(Long accountId) {
        MpAccountDO account = mpAccountService.getRequiredAccount(accountId);
        // for 循环，避免递归出意外问题，导致死循环
        String nextOpenid = null;
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            log.info("[syncUser][第({}) 次加载公众号用户列表，nextOpenid({})]", i, nextOpenid);
            try {
                nextOpenid = syncUser0(account, nextOpenid);
            } catch (WxErrorException e) {
                log.error("[syncUser][第({}) 次同步用户异常]", i, e);
                break;
            }
            // 如果 nextOpenid 为空，表示已经同步完毕
            if (StrUtil.isEmpty(nextOpenid)) {
                break;
            }
        }
    }

    private String syncUser0(MpAccountDO account, String nextOpenid) throws WxErrorException {
        // 第一步，从公众号流式加载用户
        WxMpService mpService = mpServiceFactory.getRequiredMpService(account.getId());
        WxMpUserList wxUserList = mpService.getUserService().userList(nextOpenid);
        if (CollUtil.isEmpty(wxUserList.getOpenids())) {
            return null;
        }

        // 第二步，分批加载用户信息
        List<List<String>> openidsList = CollUtil.split(wxUserList.getOpenids(), 100);
        for (List<String> openids : openidsList) {
            log.info("[syncUser][批量加载用户信息，openids({})]", openids);
            List<WxMpUser> wxUsers = mpService.getUserService().userInfoList(openids);
            batchSaveUser(account, wxUsers);
        }

        // 返回下一次的 nextOpenId
        return wxUserList.getNextOpenid();
    }

    private void batchSaveUser(MpAccountDO account, List<WxMpUser> wxUsers) {
        if (CollUtil.isEmpty(wxUsers)) {
            return;
        }
        // 1. 获得数据库已保存的用户列表
        List<MpUserDO> dbUsers = mpUserMapper.selectListByAppIdAndOpenid(account.getAppId(),
                CollectionUtils.convertList(wxUsers, WxMpUser::getOpenId));
        Map<String, MpUserDO> openId2Users = CollectionUtils.convertMap(dbUsers, MpUserDO::getOpenid);

        // 2.1 根据情况，插入或更新
        List<MpUserDO> users = MpUserConvert.INSTANCE.convertList(account, wxUsers);
        List<MpUserDO> newUsers = new ArrayList<>();
        for (MpUserDO user : users) {
            MpUserDO dbUser = openId2Users.get(user.getOpenid());
            if (dbUser == null) { // 新增：稍后批量插入
                newUsers.add(user);
            } else { // 更新：直接执行更新
                user.setId(dbUser.getId());
                mpUserMapper.updateById(user);
            }
        }
        // 2.2 批量插入
        if (CollUtil.isNotEmpty(newUsers)) {
            mpUserMapper.insertBatch(newUsers);
        }
    }

    @Override
    public void updateUserUnsubscribe(String appId, String openId) {
        MpUserDO dbUser = mpUserMapper.selectByAppIdAndOpenid(appId, openId);
        if (dbUser == null) {
            log.error("[updateUserUnsubscribe][微信公众号粉丝 appId({}) openId({}) 不存在]", appId, openId);
            return;
        }
        mpUserMapper.updateById(new MpUserDO().setId(dbUser.getId()).setSubscribeStatus(CommonStatusEnum.DISABLE.getStatus())
                .setSubscribeTime(LocalDateTime.now()));
    }

}
