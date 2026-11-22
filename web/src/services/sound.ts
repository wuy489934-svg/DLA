import { useSettings } from './settings.js'

/** 界面音效：WebAudio 合成，带轻微噪声质感与和声，不引入音频文件 */
let context: AudioContext | null = null
let master: GainNode | null = null
let noiseBuffer: AudioBuffer | null = null

const ensure = (): AudioContext | null => {
  if (!useSettings.getState().soundEnabled) {
    return null
  }
  try {
    context ??= new AudioContext()
    if (!master && context) {
      master = context.createGain()
      master.gain.value = 0.9
      master.connect(context.destination)
    }
    if (context.state === 'suspended') {
      void context.resume()
    }
    return context
  } catch {
    return null
  }
}

const noise = (ctx: AudioContext): AudioBuffer => {
  if (noiseBuffer) {
    return noiseBuffer
  }
  const length = Math.floor(ctx.sampleRate * 0.12)
  const buffer = ctx.createBuffer(1, length, ctx.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < length; i++) {
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, 2.4)
  }
  noiseBuffer = buffer
  return buffer
}

type ToneOptions = {
  frequency: number
  delay?: number
  duration?: number
  volume?: number
  type?: OscillatorType
  glide?: number
  filter?: number
}

const tone = (options: ToneOptions) => {
  const ctx = ensure()
  if (!ctx || !master) {
    return
  }
  try {
    const {
      frequency,
      delay = 0,
      duration = 0.16,
      volume = 0.03,
      type = 'sine',
      glide,
      filter,
    } = options
    const oscillator = ctx.createOscillator()
    const gain = ctx.createGain()
    oscillator.type = type
    const start = ctx.currentTime + delay
    oscillator.frequency.setValueAtTime(frequency, start)
    if (glide) {
      oscillator.frequency.exponentialRampToValueAtTime(glide, start + duration)
    }
    gain.gain.setValueAtTime(0.0001, start)
    gain.gain.linearRampToValueAtTime(volume, start + 0.012)
    gain.gain.exponentialRampToValueAtTime(0.0001, start + duration)
    let last: AudioNode = gain
    if (filter) {
      const biquad = ctx.createBiquadFilter()
      biquad.type = 'lowpass'
      biquad.frequency.value = filter
      gain.connect(biquad)
      last = biquad
    }
    oscillator.connect(gain)
    last.connect(master)
    oscillator.start(start)
    oscillator.stop(start + duration + 0.04)
  } catch {
    // 音效失败不影响功能
  }
}

/** 极短的按键颗粒感，用于选择模式/点按 */
export const playTap = () => {
  const ctx = ensure()
  if (!ctx || !master) {
    return
  }
  try {
    const source = ctx.createBufferSource()
    source.buffer = noise(ctx)
    const filter = ctx.createBiquadFilter()
    filter.type = 'bandpass'
    filter.frequency.value = 1800
    filter.Q.value = 0.9
    const gain = ctx.createGain()
    const start = ctx.currentTime
    gain.gain.setValueAtTime(0.028, start)
    gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.045)
    source.connect(filter)
    filter.connect(gain)
    gain.connect(master)
    source.start(start)
    source.stop(start + 0.06)
    tone({ frequency: 1320, duration: 0.035, volume: 0.014, type: 'triangle' })
  } catch {
    // 忽略
  }
}

export const playSend = () => {
  tone({ frequency: 560, duration: 0.1, volume: 0.03, type: 'sine', glide: 760 })
  tone({ frequency: 840, delay: 0.06, duration: 0.14, volume: 0.022, type: 'triangle' })
  const ctx = ensure()
  if (!ctx || !master) {
    return
  }
  try {
    const source = ctx.createBufferSource()
    source.buffer = noise(ctx)
    const filter = ctx.createBiquadFilter()
    filter.type = 'highpass'
    filter.frequency.value = 2600
    const gain = ctx.createGain()
    const start = ctx.currentTime + 0.01
    gain.gain.setValueAtTime(0.018, start)
    gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.07)
    source.connect(filter)
    filter.connect(gain)
    gain.connect(master)
    source.start(start)
    source.stop(start + 0.09)
  } catch {
    // 忽略
  }
}

export const playDone = () => {
  tone({ frequency: 523.25, duration: 0.24, volume: 0.026, type: 'sine' })
  tone({ frequency: 659.25, delay: 0.09, duration: 0.26, volume: 0.022, type: 'sine' })
  tone({ frequency: 783.99, delay: 0.18, duration: 0.34, volume: 0.02, type: 'triangle' })
  tone({ frequency: 1567.98, delay: 0.18, duration: 0.22, volume: 0.006, type: 'sine' })
}

export const playError = () => {
  tone({ frequency: 190, duration: 0.22, volume: 0.036, type: 'sawtooth', glide: 120, filter: 600 })
  tone({ frequency: 96, delay: 0.06, duration: 0.3, volume: 0.024, type: 'sine' })
}

export const playAsk = () => {
  tone({ frequency: 987.77, duration: 0.16, volume: 0.022, type: 'sine' })
  tone({ frequency: 1975.53, delay: 0.01, duration: 0.1, volume: 0.007, type: 'sine' })
  tone({ frequency: 740, delay: 0.11, duration: 0.2, volume: 0.016, type: 'triangle' })
}
