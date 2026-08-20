
import { logActivity } from '@/services/api.js'

/**
 * Activity Tracker
 * Tracks user active duration on a specific target (Project, File, etc.)
 * Handles idle time detection (30m) and reports rounded duration.
 */
class ActivityTracker {
    constructor() {
        this.targetId = null
        this.targetName = null
        this.startTime = null
        this.lastActiveTime = null
        this.idleThreshold = 30 * 60 * 1000 // 30 minutes
        this.idleSegments = [] // Array of { start, end }
        this.timer = null
        this.isTracking = false
        this.isRecording = false // Manual recording state
        
        // New: Active Session tracking for specific items (files/URLs)
        this.activeSession = null // { actionType, targetId, targetName, startTime, projectMeta }
        
        // Bind events
        this.resetIdleTimer = this.resetIdleTimer.bind(this)
        this.handleBlur = this.handleBlur.bind(this)
        this.handleFocus = this.handleFocus.bind(this)
    }

    setRecording(isRecording) {
        this.isRecording = isRecording
        console.log(`[ActivityTracker] Set recording state: ${this.isRecording}`)
        if (!this.isRecording) {
            this.flushActiveSession()
        }
    }

    // Toggle manual recording state
    toggleRecording() {
        this.setRecording(!this.isRecording)
        return this.isRecording
    }
    
    getRecordingState() {
        return this.isRecording
    }
    
    // Track a specific active item (File or URL) as a session
    // Replaces logAction for session-based tracking
    // projectId：结构化项目归属，供后端 /api/activity/history 按项目分类；
    // projectMeta 是旧的 "Project: 名称" 自由文本，为老展示逻辑保留兼容
    trackActivePage(actionType, targetId, targetName, projectId, projectMeta = '') {
        if (!this.isRecording) return

        // If switching to the same item, check if we need to do anything.
        // Usually switching means we came from somewhere else, or opened it.
        // Let's just flush previous and start new to be safe and accurate with segments.

        this.flushActiveSession()

        this.activeSession = {
            actionType,
            targetId,
            targetName,
            startTime: Date.now(),
            projectId,
            projectMeta
        }
        console.log(`[ActivityTracker] Started session: ${targetName} (${actionType})`)
    }

    flushActiveSession() {
        if (!this.activeSession) return

        const { actionType, targetId, targetName, startTime, projectId, projectMeta } = this.activeSession
        const endTime = Date.now()
        const duration = endTime - startTime

        // Log even short durations if precise mode requested, or at least > 100ms to avoid noise
        if (duration > 100) {
            logActivity(actionType, targetId, targetName, duration, projectMeta, projectId)
                .catch(e => console.error('[ActivityTracker] Log active session failed', e))
        }

        this.activeSession = null
    }

    // Log instant action (like OPEN_FILE, OPEN_URL)
    // Only logs if recording is enabled
    async logAction(actionType, targetId, targetName, duration = 0, metaInfo = '') {
        // Deprecated for session based tracking, but kept for compatibility if needed
        if (!this.isRecording) return
        try {
            await logActivity(actionType, targetId, targetName, duration, metaInfo)
        } catch (e) {
            console.error('[ActivityTracker] Failed to log action', e)
        }
    }

    start(targetId, targetName) {
        // Always track internally for idle detection, but only log on stop if isRecording was true
        
        if (this.isTracking) {
            this.stop()
        }
        
        this.targetId = targetId
        this.targetName = targetName
        this.startTime = Date.now()
        this.lastActiveTime = Date.now()
        this.idleSegments = []
        this.isTracking = true
        
        // Add listeners
        if (typeof window !== 'undefined') {
            window.addEventListener('mousemove', this.resetIdleTimer)
            window.addEventListener('keydown', this.resetIdleTimer)
            window.addEventListener('click', this.resetIdleTimer)
            window.addEventListener('scroll', this.resetIdleTimer)
            
            // New: Handle Window Blur/Focus for session pause
            window.addEventListener('blur', this.handleBlur)
            window.addEventListener('focus', this.handleFocus)
        }
        
        // Start checker loop
        this.timer = setInterval(() => {
            this.checkIdle()
        }, 60 * 1000) // Check every minute
        
        console.log(`[ActivityTracker] Started tracking session: ${targetName}`)
    }
    
    handleBlur() {
        // Window lost focus: End current active session
        // We keep the main "Project" tracking running (maybe?), but the specific file session should definitely stop or pause.
        // User requested: "无论是主动关闭标签页、失焦...都应该是停止时间"
        // So we flush the active file session.
        if (this.isRecording && this.activeSession) {
            console.log('[ActivityTracker] Window blur: Flushing active session')
            // Store the current session info to potentially resume on focus?
            // If user comes back to the same page, we probably want to start a new session for it.
            // We can store `lastActiveSessionInfo` to auto-resume if needed, 
            // BUT the UI might not trigger `trackActivePage` again on focus automatically unless we handle it here.
            this.lastPausedSession = { ...this.activeSession }
            this.flushActiveSession()
        }
    }
    
    handleFocus() {
        // Window gained focus
        if (this.isRecording && this.lastPausedSession) {
            console.log('[ActivityTracker] Window focus: Resuming active session')
            // Resume tracking the same item
            const { actionType, targetId, targetName, projectId, projectMeta } = this.lastPausedSession
            this.trackActivePage(actionType, targetId, targetName, projectId, projectMeta)
            this.lastPausedSession = null
        }
    }

    stop() {
        if (!this.isTracking) return
        
        const endTime = Date.now()
        this.isTracking = false
        
        // Flush any active file session
        this.flushActiveSession()
        
        // Cleanup
        if (typeof window !== 'undefined') {
            window.removeEventListener('mousemove', this.resetIdleTimer)
            window.removeEventListener('keydown', this.resetIdleTimer)
            window.removeEventListener('click', this.resetIdleTimer)
            window.removeEventListener('scroll', this.resetIdleTimer)
            window.removeEventListener('blur', this.handleBlur)
            window.removeEventListener('focus', this.handleFocus)
        }
        clearInterval(this.timer)
        
        // Only generate log if Recording was enabled AND we have a valid target
        if (!this.isRecording) {
            this.targetId = null
            this.targetName = null
            return
        }
        
        // Calculate duration
        const totalRawDuration = endTime - this.startTime
        
        // Calculate idle duration
        let totalIdleDuration = 0
        let idleMeta = []
        
        // Check if currently idle at stop time
        if (endTime - this.lastActiveTime > this.idleThreshold) {
            this.idleSegments.push({ start: this.lastActiveTime, end: endTime })
        }
        
        this.idleSegments.forEach(seg => {
            const segDur = seg.end - seg.start
            totalIdleDuration += segDur
            // Format for meta: "Idle: HH:MM - HH:MM (XXm)"
            const startStr = new Date(seg.start).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})
            const endStr = new Date(seg.end).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})
            const min = Math.floor(segDur / 60000)
            idleMeta.push(`空闲: ${startStr}-${endStr} (${min}分)`)
        })
        
        const effectiveDuration = totalRawDuration // We log TOTAL time, but note idle time in meta
        
        // Rounding logic: Round up to nearest 15s (15000ms)
        const roundedSeconds = Math.ceil(effectiveDuration / 15000) * 15
        const displayMinutes = (roundedSeconds / 60).toFixed(2)
        
        let metaInfo = ''
        if (idleMeta.length > 0) {
            metaInfo = `总时长: ${displayMinutes}分. IdleSegments: ${idleMeta.join(', ')}`
        } else {
            metaInfo = `时长: ${displayMinutes}分`
        }
        
        // Log to backend
        // actionType = 'WORK'
        if (this.targetId) {
            logActivity('WORK', this.targetId, this.targetName, effectiveDuration, metaInfo)
                .catch(err => console.error('[ActivityTracker] Log failed', err))
        }
        
        this.targetId = null
        this.targetName = null
    }

    resetIdleTimer() {
        const now = Date.now()
        // If coming back from idle
        if (now - this.lastActiveTime > this.idleThreshold) {
            // Record the idle segment that just ended
            this.idleSegments.push({
                start: this.lastActiveTime,
                end: now
            })
            console.log('[ActivityTracker] Idle segment recorded')
        }
        this.lastActiveTime = now
    }
    
    checkIdle() {
        // Just a heartbeat, actual logic is in resetIdleTimer or stop
    }
}

export const activityTracker = new ActivityTracker()
