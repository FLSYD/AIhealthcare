/**
 * 将 @vant/weapp 复制到 miniprogram_npm（本地构建 npm）
 * 运行：npm run build:npm
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const SRC = path.join(ROOT, 'node_modules', '@vant', 'weapp', 'lib')
const DEST = path.join(ROOT, 'miniprogram_npm', '@vant', 'weapp')

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true })
  for (const name of fs.readdirSync(src)) {
    const from = path.join(src, name)
    const to = path.join(dest, name)
    if (fs.statSync(from).isDirectory()) {
      copyDir(from, to)
    } else {
      fs.copyFileSync(from, to)
    }
  }
}

if (!fs.existsSync(SRC)) {
  console.error('请先运行 npm install')
  process.exit(1)
}

fs.rmSync(path.join(ROOT, 'miniprogram_npm'), { recursive: true, force: true })
copyDir(SRC, DEST)

// 小程序调试时协议相对地址会落到 HTTP，显式使用字体 CDN 的 HTTPS 地址。
const iconStylePath = path.join(DEST, 'icon', 'index.wxss')
const iconStyle = fs.readFileSync(iconStylePath, 'utf8')
fs.writeFileSync(iconStylePath, iconStyle.replace(/url\(\/\/at\.alicdn\.com\//g, 'url(https://at.alicdn.com/'))
console.log('miniprogram_npm/@vant/weapp ready')
