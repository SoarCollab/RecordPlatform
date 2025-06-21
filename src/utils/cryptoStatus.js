/**
 * Web Crypto API 状态检查和用户友好的错误处理工具
 */

import { getWebCryptoStatus, testWebCryptoAPI } from './decrypt.js';

/**
 * 显示Web Crypto API状态的用户友好消息
 * @param {Function} messageHandler - 消息处理函数 (如 ElMessage.error)
 * @returns {Promise<boolean>} 如果可用返回true
 */
export async function checkAndShowCryptoStatus(messageHandler) {
    const status = getWebCryptoStatus();
    
    if (!status.available) {
        let message = `文件解密功能不可用: ${status.reason}`;
        
        if (status.suggestions.length > 0) {
            message += '\n\n请尝试以下解决方案:';
            status.suggestions.forEach((suggestion) => {
                message += `\n• ${suggestion}`;
            });
        }
        
        if (messageHandler) {
            messageHandler({
                message: message,
                type: 'error',
                duration: 10000, // 显示10秒
                showClose: true
            });
        } else {
            console.error(message);
            alert(message);
        }
        
        return false;
    }
    
    return true;
}

/**
 * 运行完整的Web Crypto API测试并显示结果
 * @param {Function} messageHandler - 消息处理函数
 * @returns {Promise<boolean>} 测试是否通过
 */
export async function runCryptoTest(messageHandler) {
    try {
        const testResult = await testWebCryptoAPI();
        
        if (!testResult.available) {
            await checkAndShowCryptoStatus(messageHandler);
            return false;
        }
        
        if (!testResult.testPassed) {
            const message = `Web Crypto API 功能测试失败: ${testResult.error || '未知错误'}`;
            if (messageHandler) {
                messageHandler({
                    message: message,
                    type: 'error',
                    duration: 8000,
                    showClose: true
                });
            } else {
                console.error(message);
                alert(message);
            }
            return false;
        }
        
        // 测试通过
        if (messageHandler) {
            messageHandler({
                message: 'Web Crypto API 功能正常，可以进行文件解密',
                type: 'success',
                duration: 3000
            });
        }
        
        return true;
        
    } catch (error) {
        const message = `Web Crypto API 测试过程中发生错误: ${error.message}`;
        if (messageHandler) {
            messageHandler({
                message: message,
                type: 'error',
                duration: 8000,
                showClose: true
            });
        } else {
            console.error(message);
            alert(message);
        }
        return false;
    }
}

/**
 * 获取当前环境的安全上下文信息
 * @returns {object} 环境信息
 */
export function getEnvironmentInfo() {
    if (typeof window === 'undefined') {
        return {
            environment: 'server',
            secure: false,
            protocol: 'unknown',
            hostname: 'unknown'
        };
    }
    
    const protocol = window.location.protocol;
    const hostname = window.location.hostname;
    const port = window.location.port;
    
    const isSecure = protocol === 'https:' || 
                    hostname === 'localhost' || 
                    hostname === '127.0.0.1' ||
                    hostname.endsWith('.localhost');
    
    return {
        environment: 'browser',
        secure: isSecure,
        protocol: protocol,
        hostname: hostname,
        port: port,
        fullUrl: window.location.href,
        userAgent: navigator.userAgent
    };
}

/**
 * 生成环境诊断报告
 * @returns {string} 诊断报告文本
 */
export function generateDiagnosticReport() {
    const env = getEnvironmentInfo();
    const cryptoStatus = getWebCryptoStatus();
    
    let report = '=== Web Crypto API 诊断报告 ===\n\n';
    
    report += '环境信息:\n';
    report += `- 协议: ${env.protocol}\n`;
    report += `- 主机: ${env.hostname}\n`;
    report += `- 端口: ${env.port || '默认'}\n`;
    report += `- 完整URL: ${env.fullUrl}\n`;
    report += `- 安全上下文: ${env.secure ? '是' : '否'}\n`;
    report += `- 用户代理: ${env.userAgent}\n\n`;
    
    report += 'Web Crypto API 状态:\n';
    report += `- 可用性: ${cryptoStatus.available ? '可用' : '不可用'}\n`;
    report += `- 原因: ${cryptoStatus.reason}\n`;
    
    if (cryptoStatus.suggestions.length > 0) {
        report += '- 建议解决方案:\n';
        cryptoStatus.suggestions.forEach((suggestion, index) => {
            report += `  ${index + 1}. ${suggestion}\n`;
        });
    }
    
    report += '\n=== 报告结束 ===';
    
    return report;
}
