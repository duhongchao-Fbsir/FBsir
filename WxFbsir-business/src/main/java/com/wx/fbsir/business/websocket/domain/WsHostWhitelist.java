package com.wx.fbsir.business.websocket.domain;

import com.wx.fbsir.common.annotation.Excel;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * WebSocket主机白名单实体
 *
 * @author wxfbsir
 * @date 2025-12-15
 */
public class WsHostWhitelist implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    @Excel(name = "主机ID")
    private String hostId;
    @Excel(name = "主机名称")
    private String hostName;
    @Excel(name = "负责人")
    private String ownerName;
    @Excel(name = "联系方式")
    private String ownerContact;
    @Excel(name = "是否团队", readConverterExp = "0=个人,1=团队")
    private Integer isTeam;
    @Excel(name = "团队名称")
    private String teamName;
    @Excel(name = "允许IP")
    private String allowedIps;
    @Excel(name = "启用状态", readConverterExp = "0=禁用,1=启用")
    private Integer status;
    @Excel(name = "过期时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;
    @Excel(name = "备注")
    private String remark;
    private Integer delFlag;
    @Excel(name = "创建者")
    private String createBy;
    @Excel(name = "创建时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;
    @Excel(name = "主机类型")
    private String hostType;
    @Excel(name = "健康检查URL")
    private String healthCheckUrl;
    @Excel(name = "在线状态")
    private String onlineStatus;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getOwnerContact() { return ownerContact; }
    public void setOwnerContact(String ownerContact) { this.ownerContact = ownerContact; }
    public Integer getIsTeam() { return isTeam; }
    public void setIsTeam(Integer isTeam) { this.isTeam = isTeam; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getAllowedIps() { return allowedIps; }
    public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public String getHostType() { return hostType; }
    public void setHostType(String hostType) { this.hostType = hostType; }
    public String getHealthCheckUrl() { return healthCheckUrl; }
    public void setHealthCheckUrl(String healthCheckUrl) { this.healthCheckUrl = healthCheckUrl; }
    public String getOnlineStatus() { return onlineStatus; }
    public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
}
