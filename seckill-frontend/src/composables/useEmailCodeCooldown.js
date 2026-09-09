import { onBeforeUnmount, ref } from 'vue'

// Lock synchronously before the first await so rapid clicks cannot enter twice.
export function useEmailCodeCooldown(duration = 60) {
  const sending = ref(false)
  const countdown = ref(0)
  let timer = null

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function begin() {
    if (sending.value || countdown.value > 0) return false
    sending.value = true
    countdown.value = duration
    stopTimer()
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) {
        countdown.value = 0
        stopTimer()
      }
    }, 1000)
    return true
  }

  function fail() {
    sending.value = false
    countdown.value = 0
    stopTimer()
  }

  function finish() {
    sending.value = false
  }

  onBeforeUnmount(stopTimer)

  return { sending, countdown, begin, fail, finish }
}
