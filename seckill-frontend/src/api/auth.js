import request from '../utils/request'

export const getCaptcha = () => request.get('/captcha')
export const loginApi = payload => request.post('/auth/login', payload)
export const codeLoginApi = payload => request.post('/auth/login/code', payload)
export const registerApi = payload => request.post('/auth/register', payload)
export const sendEmailCodeApi = payload => request.post('/auth/send-code', payload)
export const resetPasswordApi = payload => request.post('/auth/reset-password', payload)
export const logoutApi = refreshToken => request.post('/auth/logout', { refreshToken }, { skipAuthRefresh: true })
