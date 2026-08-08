<template>
  <view class="login-page" @mousemove="handleMouseMove">
    <!-- Background Elements -->
    <view class="bg-gradient"></view>
    <view class="bg-mesh"></view>

    <!-- Top Navigation -->
    <view class="top-nav">
      <view class="nav-left">
        <image class="nav-logo" src="/static/logo_full_v2.png" mode="heightFix" />
      </view>
      <view class="nav-right">
        <text class="nav-item">使用指引</text>
        <text class="nav-item">帮助中心</text>
        <text class="nav-item">官网</text>
      </view>
    </view>

    <view class="main-layout">
      <!-- Left Column: 3D Device Showcase -->
      <view class="showcase-section">
        <view 
          class="device-wrapper"
          :style="{ transform: deviceTransform }"
        >
          <view class="device-frame">
            <!-- Simulated IDE Interface -->
            <view class="ide-window">
              <view class="ide-titlebar">
                <view class="window-controls">
                  <view class="control red"></view>
                  <view class="control yellow"></view>
                  <view class="control green"></view>
                </view>
                <text class="window-title">AI Workdeck - Professional Workspace</text>
              </view>
              <view class="ide-body">
                <view class="ide-sidebar">
                  <view class="sidebar-icon active"></view>
                  <view class="sidebar-icon"></view>
                  <view class="sidebar-icon"></view>
                </view>
                <view class="ide-explorer">
                  <view class="explorer-item">Project Alpha</view>
                  <view class="explorer-item indent">立案文件</view>
                  <view class="explorer-item indent active">尽职调查</view>
                  <view class="explorer-item indent">法律意见书</view>
                </view>
                <view class="ide-editor">
                  <view class="editor-tabs">
                    <view class="tab active">尽职调查报告.doc</view>
                    <view class="tab">证据清单.xlsx</view>
                  </view>
                  <view class="doc-area">
                    <view class="doc-title">关于 Project Alpha 的法律尽职调查报告</view>
                    <view class="doc-meta">
                      <text>致：客户委员会</text>
                      <text style="margin-left: 20px;">日期：2025-12-17</text>
                    </view>
                    <view class="doc-content">
                      <view class="doc-paragraph">
                        <text>第一章 法律尽职调查概述</text>
                      </view>
                      <view class="doc-paragraph text-body">
                        <text>本次尽职调查旨在全面评估目标公司的法律合规性，包括但不限于公司设立与存续、主要资产、重大债权债务、税务合规等方面。</text>
                      </view>
                      <view class="doc-paragraph text-body">
                        <text>我们查阅了目标公司提供的工商档案、合同文件及相关的政府批文，并对关键管理人员进行了访谈...</text>
                      </view>
                      <!-- Skeleton lines for "blank" look -->
                      <view class="skeleton-line" style="width: 90%"></view>
                      <view class="skeleton-line" style="width: 95%"></view>
                      <view class="skeleton-line" style="width: 80%"></view>
                      <view class="skeleton-line" style="width: 85%"></view>
                    </view>
                  </view>
                  
                  <!-- Subtle Monogram Watermark inside IDE -->
                  <image class="ide-watermark" src="/static/monochrome.png" mode="aspectFit" />
                </view>
              </view>
            </view>
            <!-- Screen Reflection/Gloss -->
            <view class="screen-gloss"></view>
          </view>
          <!-- Device Edge Highlight -->
          <view class="device-edge"></view>
          <!-- Shadow -->
          <view class="device-shadow"></view>
        </view>
      </view>

      <!-- Right Column: Glassmorphism Login Card -->
      <view class="login-section">
        <view class="glass-card">
          <view class="card-header">
            <image class="card-logo" src="/static/iconmark_v2.png" mode="heightFix" />
            <view class="card-texts">
              <text class="product-name">AI Workdeck</text>
              <text class="product-subtitle">一站式AI文档工作台</text>
            </view>
          </view>

          <!-- Tabs -->
          <view class="auth-tabs">
            <view class="tab-btn" :class="{ active: activeTab === 'login' }" @tap="switchTab('login')">登录</view>
            <view class="tab-btn" :class="{ active: activeTab === 'register' }" @tap="switchTab('register')">注册</view>
            <view class="tab-btn" :class="{ active: activeTab === 'client' }" @tap="switchTab('client')">客户</view>
            <view class="tab-indicator" :style="indicatorStyle"></view>
          </view>

          <!-- Login Form -->
          <view v-if="activeTab === 'login' && !smsStep" class="form-body swing-in">
            <view class="input-group">
              <text class="label">用户名</text>
              <input class="glass-input" type="text" v-model="loginForm.username" placeholder="请输入用户名" placeholder-class="placeholder-style" />
            </view>
            <view class="input-group">
              <text class="label">密码</text>
              <input class="glass-input" type="password" v-model="loginForm.password" @confirm="handleLogin" placeholder="请输入密码" placeholder-class="placeholder-style" />
            </view>
            <view class="form-options">
               <view class="remember-me">
                 <checkbox style="transform:scale(0.7)" color="#5BD197" checked />
                 <text>记住我</text>
               </view>
               <text class="link-text">忘记密码?</text>
            </view>
            <button class="action-btn" :loading="loginLoading" @tap="handleLogin">登 录</button>
          </view>

          <!-- Second Factor Step（登录二次验证：认证器 / 邮箱 / 短信） -->
          <view v-else-if="activeTab === 'login' && smsStep" class="form-body swing-in">
            <view class="input-group">
              <text class="label">{{ secondFactorLabel }}</text>
              <text class="sms-hint">
                {{ smsMethod === 'totp' ? '请输入认证器 App 上当前显示的 6 位验证码' : '验证码已发送至 ' + smsPhoneMasked }}
              </text>
              <input class="glass-input" type="number" maxlength="6" v-model="smsCodeInput" @confirm="handleSmsLogin" placeholder="请输入 6 位验证码" placeholder-class="placeholder-style" />
            </view>
            <view class="form-options">
              <text class="link-text" @tap="backToPassword">返回</text>
              <text v-if="smsMethod !== 'totp'" class="link-text" :class="{ disabled: smsCountdown > 0 }" @tap="resendSmsCode">
                {{ smsCountdown > 0 ? smsCountdown + 's 后可重发' : '重新发送' }}
              </text>
            </view>
            <button class="action-btn" :loading="loginLoading" @tap="handleSmsLogin">验证并登录</button>
          </view>

          <!-- Register Form -->
          <view v-else-if="activeTab === 'register'" class="form-body swing-in">
            <view class="input-group">
              <text class="label">用户名</text>
              <input class="glass-input" type="text" v-model="registerForm.username" placeholder="6-20个字符" placeholder-class="placeholder-style" />
            </view>
            <view class="input-group">
              <text class="label">显示名称</text>
              <input class="glass-input" type="text" v-model="registerForm.displayName" placeholder="可选" placeholder-class="placeholder-style" />
            </view>
            <view class="input-group">
              <text class="label">密码</text>
              <input class="glass-input" type="password" v-model="registerForm.password" placeholder="至少6位" placeholder-class="placeholder-style" />
            </view>
            <view class="input-group">
              <text class="label">确认密码</text>
              <input class="glass-input" type="password" v-model="registerForm.passwordConfirm" @confirm="handleRegister" placeholder="再次输入密码" placeholder-class="placeholder-style" />
            </view>
            <button class="action-btn" :loading="registerLoading" @tap="handleRegister">注 册</button>
          </view>

          <!-- Client Form -->
          <view v-else class="form-body swing-in">
            <view class="input-group">
              <text class="label">案卷访问码</text>
              <input class="glass-input" type="text" v-model="clientForm.accessCode" @confirm="handleClientLogin" placeholder="请输入律师发给你的访问码" placeholder-class="placeholder-style" />
            </view>
            <button class="action-btn" :loading="clientLoginLoading" @tap="handleClientLogin">进入案卷</button>
          </view>
          
          <view class="card-footer">
            <text>让法律人聚焦专业判断</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { login, register, clientLogin, getWizardStatus, getMyProjects, sendSmsCode, sendMailCode } from '@/services/api.js'
import { saveSession, getSessionId, getCurrentUser } from '@/utils/auth.js'
import { syncRecentToMenu } from '@/utils/recentProjects.js'

export default {
  name: 'Login',
  onLoad() {
    // 首次运行（未初始化）时跳转设置向导（Epic #18 T4）；
    // 已初始化或后端不可达则正常停留在登录页
    getWizardStatus()
      .then((res) => {
        if (res && res.initialized === false) {
          uni.reLaunch({ url: '/pages/wizard/wizard' })
        } else {
          // IDE 化启动直达：存储会话仍有效则跳过登录，直接回到上次的工作现场
          this.tryAutoResume()
        }
      })
      .catch((e) => {
        console.warn('查询向导状态失败（忽略）:', e)
      })
  },
  data() {
    return {
      activeTab: 'login',
      mouseXPercent: 0, // 0 to 1
      loginForm: {
        username: '',
        password: '',
      },
      registerForm: {
        username: '',
        displayName: '',
        password: '',
        passwordConfirm: '',
      },
      clientForm: {
        accessCode: '',
        displayName: ''
      },
      loginLoading: false,
      registerLoading: false,
      clientLoginLoading: false,
      // 登录二次验证步骤（后端返回 4005 时进入）；method: 'totp' | 'sms'
      smsStep: false,
      smsMethod: 'sms',
      smsPhoneMasked: '',
      smsCodeInput: '',
      smsCountdown: 0,
      smsCountdownTimer: null
    }
  },
  onUnload() {
    if (this.smsCountdownTimer) clearInterval(this.smsCountdownTimer)
  },
  computed: {
    // 二次验证的方式由后端定（TOTP > 邮箱 > 短信），前端只负责说人话
    secondFactorLabel() {
      if (this.smsMethod === 'totp') return '认证器验证';
      if (this.smsMethod === 'mail') return '邮箱验证';
      return '短信验证';
    },
    deviceTransform() {
      // Logic:
      // When mouse is at left (low %), device is tilted: rotateY(25deg) rotateX(5deg) scale(0.9)
      // When mouse is at right (high %), device is facing front: rotateY(0deg) rotateX(0deg) scale(1)
      // User Request: Reach "straight" state when mouse reaches the login card (approx 60% width)
      
      // Define a threshold (e.g., 0.6 means 60% of screen width)
      const threshold = 0.6;
      let rawP = this.mouseXPercent / threshold;
      
      // Clamp between 0 and 1
      const p = Math.min(Math.max(rawP, 0), 1);
      
      const rotY = 25 * (1 - p); // 25deg -> 0deg
      const rotX = 10 * (1 - p);  // 10deg -> 0deg
      const scale = 0.95 + (0.05 * p); // 0.95 -> 1.0
      const translateX = -50 * (1 - p); // Slide in slightly from left
      
      return `perspective(2000px) rotateY(${rotY}deg) rotateX(${rotX}deg) scale(${scale}) translateX(${translateX}px)`;
    },
    indicatorStyle() {
      // Simple logic to move the tab indicator
      const index = ['login', 'register', 'client'].indexOf(this.activeTab);
      return {
        left: `${index * 33.33}%`
      }
    }
  },
  methods: {
    // IDE 化启动直达：会话有效 → 直进上次项目（像 VS Code 打开即回到上个工作区）；
    // 会话失效/网络异常 → 静默停留登录页。CLIENT 账号视图受限，只回个人中心。
    async tryAutoResume() {
      const sid = getSessionId()
      const user = getCurrentUser()
      if (!sid || !user) return
      try {
        const projects = await getMyProjects() // 顺带校验会话有效性
        if (user.role === 'CLIENT') {
          uni.reLaunch({ url: '/pages/userprofile/userprofile' })
          return
        }
        const list = Array.isArray(projects) ? projects : (projects && projects.data) || []
        syncRecentToMenu(list) // 应用菜单「最近打开」子菜单
        const lastId = Number(uni.getStorageSync('checkba_last_project_id') || 0)
        if (lastId && list.some((p) => Number(p.id) === lastId)) {
          uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${lastId}` })
        } else {
          uni.reLaunch({ url: '/pages/userprofile/userprofile' })
        }
      } catch (e) {
        console.warn('会话恢复失败，停留登录页:', e && e.message)
      }
    },
    handleMouseMove(e) {
      // #ifdef H5
      // On H5 we can track mouse. On App/Mobile touch might be different, but request is Desktop-like
      const width = window.innerWidth;
      const x = e.pageX; // or clientX
      this.mouseXPercent = x / width;
      // #endif
    },
    switchTab(tab) {
      this.activeTab = tab;
      this.loginForm = { username: '', password: '' };
      this.registerForm = { username: '', displayName: '', password: '', passwordConfirm: '' };
      this.clientForm = { accessCode: '' };
      this.resetSmsStep();
    },
    resetSmsStep() {
      this.smsStep = false;
      this.smsMethod = 'sms';
      this.smsPhoneMasked = '';
      this.smsCodeInput = '';
      this.smsCountdown = 0;
      if (this.smsCountdownTimer) {
        clearInterval(this.smsCountdownTimer);
        this.smsCountdownTimer = null;
      }
    },
    backToPassword() {
      this.resetSmsStep();
    },
    startSmsCountdown() {
      this.smsCountdown = 60;
      if (this.smsCountdownTimer) clearInterval(this.smsCountdownTimer);
      this.smsCountdownTimer = setInterval(() => {
        if (this.smsCountdown > 0) {
          this.smsCountdown--;
        } else {
          clearInterval(this.smsCountdownTimer);
          this.smsCountdownTimer = null;
        }
      }, 1000);
    },
    async resendSmsCode() {
      if (this.smsCountdown > 0) return;
      try {
        // 二次验证走哪条通道由后端 4005 信封里的 method 决定，发码就得打对应端点——
        // 邮箱用户打短信端点会被判成「未绑定手机号」
        const payload = {
          scene: 'login',
          username: this.loginForm.username,
          password: this.loginForm.password
        };
        const res = this.smsMethod === 'mail' ? await sendMailCode(payload) : await sendSmsCode(payload);
        // 邮箱通道回 emailMasked，短信回 phoneMasked；两者都填进同一个展示位
        const masked = res.data && (res.data.emailMasked || res.data.phoneMasked);
        if (masked) this.smsPhoneMasked = masked;
        this.startSmsCountdown();
        uni.showToast({ title: '验证码已发送', icon: 'none' });
      } catch (e) {
        uni.showToast({ title: e.message || '发送失败', icon: 'none' });
      }
    },
    async handleSmsLogin() {
      if (!this.smsCodeInput || this.smsCodeInput.length < 6) {
        uni.showToast({ title: '请输入 6 位验证码', icon: 'none' });
        return;
      }
      this.loginLoading = true;
      try {
        const res = await login(this.loginForm.username, this.loginForm.password, this.smsCodeInput);
        if (res.code === 0 && res.data) {
          this.resetSmsStep();
          this.finishLogin(res);
        } else {
          uni.showToast({ title: res.message || '验证失败', icon: 'none' });
        }
      } catch (error) {
        this.smsCodeInput = '';
        uni.showToast({ title: error.message || '验证失败', icon: 'none' });
      } finally {
        this.loginLoading = false;
      }
    },
    finishLogin(res) {
      saveSession(res.data.sessionId, res.data.user);
      if (!getSessionId()) throw new Error('Session Save Failed');
      uni.showToast({ title: '登录成功', icon: 'success' });
      setTimeout(() => {
        uni.reLaunch({ url: '/pages/userprofile/userprofile' });
      }, 300);
    },
    async handleClientLogin() {
      if (!this.clientForm.accessCode) {
        uni.showToast({ title: '请输入律师发给你的访问码', icon: 'none' });
        return;
      }
      this.clientLoginLoading = true;
      try {
        const res = await clientLogin(this.clientForm.accessCode, null);
        if (res.code === 0 && res.data) {
          saveSession(res.data.sessionId, res.data.user);
          uni.showToast({ title: '登录成功', icon: 'success' });
          const projectId = res.data.projectId;
          setTimeout(() => {
            uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${projectId}` });
          }, 300);
        } else {
          uni.showToast({ title: res.message || '登录失败', icon: 'none' });
        }
      } catch (e) {
        uni.showToast({ title: e.message || '登录失败', icon: 'none' });
      } finally {
        this.clientLoginLoading = false;
      }
    },
    async handleLogin() {
      if (!this.loginForm.username || !this.loginForm.password) {
        uni.showToast({ title: '请输入用户名和密码', icon: 'none' });
        return;
      }
      this.loginLoading = true;
      try {
        const res = await login(this.loginForm.username, this.loginForm.password);
        if (res.code === 0 && res.data) {
          this.finishLogin(res);
        } else {
          uni.showToast({ title: res.message || '登录失败', icon: 'none' });
        }
      } catch (error) {
        if (error && error.smsRequired) {
          // 密码已对，进入二次验证步骤。认证器方式的码在用户手机 App 里，不必发短信
          this.smsStep = true;
          this.smsMethod = (error.data && error.data.method) || 'sms';
          this.smsPhoneMasked = (error.data && error.data.phoneMasked) || '';
          this.smsCodeInput = '';
          if (this.smsMethod !== 'totp') this.resendSmsCode();
          return;
        }
        console.error('Login Failed', error);
        uni.showToast({ title: error.message || '登录失败', icon: 'none' });
      } finally {
        this.loginLoading = false;
      }
    },
    async handleRegister() {
      if (!this.registerForm.username || !this.registerForm.password) {
        uni.showToast({ title: '请输入用户名和密码', icon: 'none' });
        return;
      }
      if (this.registerForm.password.length < 6) {
        uni.showToast({ title: '密码长度不能少于6位', icon: 'none' });
        return;
      }
      if (this.registerForm.password !== this.registerForm.passwordConfirm) {
        uni.showToast({ title: '两次输入的密码不一致', icon: 'none' });
        return;
      }
      this.registerLoading = true;
      try {
        const res = await register(
          this.registerForm.username,
          this.registerForm.password,
          this.registerForm.displayName || this.registerForm.username
        );
        if (res.code === 0 && res.data) {
          saveSession(res.data.sessionId, res.data.user);
          uni.showToast({ title: '注册成功', icon: 'success' });
          setTimeout(() => {
            uni.reLaunch({ url: '/pages/userprofile/userprofile' });
          }, 500);
        } else {
          uni.showToast({ title: res.message || '注册失败', icon: 'none' });
        }
      } catch (error) {
        console.error('Register Failed', error);
        uni.showToast({ title: error.message || '注册失败', icon: 'none' });
      } finally {
        this.registerLoading = false;
      }
    }
  }
}
</script>

<style lang="scss" scoped>
/* Color Config */
$color-primary: #1A5336; // Forest Green
$color-accent: #5BD197; // Mint Green
$color-text-main: #2C3338;
$color-text-light: #6C757D;
$bg-dark: #212629;
$glass-white: rgba(255, 255, 255, 0.75);
$glass-border: rgba(255, 255, 255, 0.5);

.login-page {
  width: 100vw;
  height: 100vh;
  position: relative;
  overflow: hidden;
  background-color: #F8F9FA;
  display: flex;
  flex-direction: column;
}

.bg-gradient {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background: radial-gradient(circle at 10% 20%, rgba(91, 209, 151, 0.15) 0%, transparent 40%),
              radial-gradient(circle at 90% 80%, rgba(26, 83, 54, 0.1) 0%, transparent 40%);
  z-index: 0;
}

.bg-mesh {
  position: absolute;
  inset: 0;
  // Subtle mesh pattern
  background-image: linear-gradient(rgba(26, 83, 54, 0.03) 1px, transparent 1px),
  linear-gradient(90deg, rgba(26, 83, 54, 0.03) 1px, transparent 1px);
  background-size: 40px 40px;
  z-index: 0;
}

.top-nav {
  position: relative;
  z-index: 10;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px 48px;
}

.nav-logo {
  height: 32px;
  width: auto;
}

.nav-right {
  display: flex;
  gap: 32px;
}
.nav-item {
  font-size: 14px;
  color: $color-text-light;
  cursor: pointer;
  transition: color 0.3s;
  &:hover { color: $color-primary; }
}

.main-layout {
  position: relative;
  z-index: 5;
  flex: 1;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  padding: 0 4vw;
}

/* 3D Showcase Section */
.showcase-section {
  flex: 1.2;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  perspective: 2000px; // Deep perspective
}

.device-wrapper {
  position: relative;
  width: 680px;
  height: 460px;
  transition: transform 0.1s linear; // Smooth follow
  transform-style: preserve-3d;
}

.device-frame {
  width: 100%;
  height: 100%;
  background: #2a2f34;
  border-radius: 12px;
  padding: 12px;
  box-shadow: 
    inset 0 0 0 2px #444,
    0 20px 50px rgba(0,0,0,0.3);
  position: relative;
  overflow: hidden;
  transform: translateZ(20px); // Pop out
  background-clip: padding-box;
}

.ide-window {
  width: 100%;
  height: 100%;
  background: #1e1e1e;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: 'Fira Code', 'Monaco', monospace;
}

.ide-titlebar {
  height: 32px;
  background: #252526;
  display: flex;
  align-items: center;
  padding: 0 10px;
  border-bottom: 1px solid #333;
}

.window-controls {
  display: flex;
  gap: 6px;
  margin-right: 16px;
  .control { width: 10px; height: 10px; border-radius: 50%; }
  .red { background: #ff5f56; }
  .yellow { background: #ffbd2e; }
  .green { background: #27c93f; }
}

.window-title {
  color: #999;
  font-size: 12px;
}

.ide-body {
  flex: 1;
  display: flex;
}

.ide-sidebar {
  width: 48px;
  background: #333333;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 10px;
  gap: 15px;
  .sidebar-icon {
    width: 24px; height: 24px; background: #666; border-radius: 4px;
    &.active { background: $color-accent; }
  }
}

.ide-explorer {
  width: 160px;
  background: #252526;
  border-right: 1px solid #333;
  padding: 10px;
  .explorer-item {
    color: #ccc; font-size: 12px; line-height: 24px;
    &.indent { padding-left: 15px; }
    &.active { background: #37373d; color: #fff; }
  }
}

.ide-editor {
  flex: 1;
  background: #1e1e1e;
  display: flex;
  flex-direction: column;
  position: relative;
}

.editor-tabs {
  height: 30px;
  background: #252526;
  display: flex;
  .tab {
    padding: 0 15px;
    font-size: 12px; color: #999;
    display: flex; align-items: center;
    background: #2d2d2d;
    &.active { background: #1e1e1e; color: #fff; border-top: 2px solid $color-accent; }
  }
}

.doc-area {
  padding: 30px 40px;
  background: #fff; /* White paper background for doc view */
  flex: 1;
  color: #333;
  font-family: 'Times New Roman', serif; /* Serif for legal docs */
  overflow: hidden;
  position: relative;
}

.doc-title {
  font-size: 16px;
  font-weight: bold;
  text-align: center;
  margin-bottom: 20px;
  color: #000;
}

.doc-meta {
  font-size: 10px;
  color: #666;
  margin-bottom: 24px;
  display: flex;
  justify-content: flex-end;
}

.doc-paragraph {
  font-size: 11px;
  line-height: 1.8;
  margin-bottom: 12px;
  font-weight: bold;
  
  &.text-body {
    font-weight: normal;
    text-indent: 2em;
    color: #444;
  }
}

.skeleton-line {
  height: 8px;
  background: #f0f0f0;
  margin-bottom: 12px;
  border-radius: 2px;
}

// Ensure watermark blends with white background
.ide-watermark {
  position: absolute;
  bottom: 20px;
  right: 20px;
  width: 100px;
  height: 100px;
  opacity: 0.05;
  pointer-events: none;
  mix-blend-mode: multiply;
}

.device-edge {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  border-radius: 12px;
  box-shadow: 
    inset 2px 2px 4px rgba(255,255,255,0.1),
    inset -2px -2px 4px rgba(0,0,0,0.5);
  pointer-events: none;
  z-index: 10;
}

.screen-gloss {
  position: absolute;
  top: 0; left: 0; right: 0; height: 60%;
  background: linear-gradient(135deg, rgba(255,255,255,0.03) 0%, transparent 60%);
  pointer-events: none;
  z-index: 5;
}

/* Glass Login Card */
.login-section {
  flex: 0.8;
  display: flex;
  justify-content: center;
  perspective: 1000px;
}

.glass-card {
  width: 440px;
  background: $glass-white;
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid $glass-border;
  border-radius: 20px;
  padding: 40px;
  box-shadow: 
    0 16px 48px rgba(26, 83, 54, 0.1),
    0 4px 12px rgba(0,0,0,0.05);
  display: flex;
  flex-direction: column;
}

.card-header {
  display: flex;
  align-items: center;
  margin-bottom: 32px;
  gap: 12px;
}
.card-logo {
  height: 48px;
  width: auto;
}
.card-texts {
  display: flex;
  flex-direction: column;
}
.product-name {
  font-size: 24px;
  font-weight: 700;
  color: $color-primary;
  letter-spacing: -0.5px;
}
.product-subtitle {
  font-size: 13px;
  color: $color-text-light;
  letter-spacing: 0.5px;
}

.auth-tabs {
  display: flex;
  position: relative;
  border-bottom: 2px solid rgba(0,0,0,0.05);
  margin-bottom: 28px;
}
.tab-btn {
  flex: 1;
  text-align: center;
  padding: 12px 0;
  font-size: 15px;
  color: $color-text-light;
  cursor: pointer;
  &.active {
    color: $color-primary;
    font-weight: 600;
  }
}
.tab-indicator {
  position: absolute;
  bottom: -2px;
  width: 33.33%;
  height: 2px;
  background: $color-accent;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.input-group {
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.label {
  font-size: 13px;
  color: $color-text-main;
  font-weight: 500;
}
.glass-input {
  height: 48px;
  background: rgba(255,255,255,0.6);
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 8px;
  padding: 0 16px;
  font-size: 15px;
  transition: all 0.2s;
  &:focus {
    background: #fff;
    border-color: $color-accent;
    box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.2);
  }
}
.placeholder-style {
  color: #aaa;
}

.form-options {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  font-size: 13px;
  color: $color-text-light;
}
.remember-me {
  display: flex;
  align-items: center;
}
.link-text {
  color: $color-primary;
  cursor: pointer;

  &.disabled {
    opacity: 0.5;
    pointer-events: none;
  }
}

.sms-hint {
  display: block;
  font-size: 12px;
  color: $color-text-light;
  margin-bottom: 6px;
}

.action-btn {
  width: 100%;
  height: 50px;
  background: $color-primary;
  color: #fff;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  cursor: pointer;
  transition: background 0.2s;
  &:active {
    background: darken($color-primary, 5%);
  }
  &::after { border: none; } // uni-app button reset
}

.card-footer {
  margin-top: 32px;
  text-align: center;
  font-size: 12px;
  color: #aaa;
}

/* Animations */
.swing-in {
  animation: swing-in-top-fwd 0.4s cubic-bezier(0.250, 0.460, 0.450, 0.940) both;
}
@keyframes swing-in-top-fwd {
  0% { transform: translateY(-10px); opacity: 0; }
  100% { transform: translateY(0); opacity: 1; }
}
</style>
