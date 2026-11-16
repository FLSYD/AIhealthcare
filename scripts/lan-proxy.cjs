#!/usr/bin/env node
'use strict'

const net = require('node:net')

const UPSTREAM_HOST = '127.0.0.1'
const DEFAULT_LISTEN_PORT = 18080

const activeClients = new Set()
let server = null
let shuttingDown = false

function isStrictIPv4(value) {
  if (typeof value !== 'string') {
    return false
  }

  const parts = value.split('.')
  if (parts.length !== 4) {
    return false
  }

  return parts.every((part) => {
    return /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255
  })
}

function isRfc1918IPv4(value) {
  if (!isStrictIPv4(value) || net.isIP(value) !== 4) {
    return false
  }

  const [first, second] = value.split('.').map(Number)
  return first === 10 ||
    (first === 172 && second >= 16 && second <= 31) ||
    (first === 192 && second === 168)
}

function parsePort(value) {
  const text = value === undefined ? String(DEFAULT_LISTEN_PORT) : value
  if (!/^\d+$/.test(text)) {
    throw new Error('监听端口必须是十进制整数')
  }

  const port = Number(text)
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) {
    throw new Error('监听端口必须在 1 到 65535 之间')
  }
  return port
}

function parseArguments(args) {
  if (args.length === 0) {
    throw new Error('必须提供监听用的 RFC1918 IPv4 地址')
  }
  if (args.length > 2) {
    throw new Error('仅接受监听 IPv4 和可选监听端口，不接受外部上游参数')
  }
  if (!isRfc1918IPv4(args[0])) {
    throw new Error('监听地址必须是 RFC1918 私有 IPv4，不能是 0.0.0.0、回环或 VPN 地址')
  }

  const listenPort = parsePort(args[1])
  // 只转发本项目的后端和管理端，不能指定其他服务作为上游。
  if (listenPort !== 18080 && listenPort !== 15173) {
    throw new Error('仅允许转发本项目端口 18080 或 15173')
  }
  return {
    listenHost: args[0],
    listenPort
  }
}

function closePair(client, getUpstream, release) {
  release()
  const upstream = getUpstream()
  if (!client.destroyed) {
    client.destroy()
  }
  if (upstream && !upstream.destroyed) {
    upstream.destroy()
  }
}

function handleClient(client, upstreamPort) {
  if (shuttingDown) {
    client.destroy()
    return
  }

  activeClients.add(client)
  let upstream = null
  let released = false

  const release = () => {
    if (released) {
      return
    }
    released = true
    activeClients.delete(client)
  }
  const releasePair = () => closePair(client, () => upstream, release)

  client.once('error', releasePair)
  client.once('close', releasePair)

  upstream = net.createConnection({
    host: UPSTREAM_HOST,
    port: upstreamPort
  })
  upstream.once('error', releasePair)
  upstream.once('close', releasePair)
  upstream.once('connect', () => {
    if (released) {
      return
    }
    client.pipe(upstream)
    upstream.pipe(client)
  })
}

function stopServer(exitCode, reason) {
  if (shuttingDown) {
    return
  }

  shuttingDown = true
  if (reason) {
    console.error(`LAN proxy 正在关闭：${reason}`)
  }

  for (const client of activeClients) {
    client.destroy()
  }
  activeClients.clear()

  if (!server) {
    process.exit(exitCode)
    return
  }

  server.close(() => {
    process.exit(exitCode)
  })

  setTimeout(() => {
    process.exit(exitCode)
  }, 1500).unref()
}

function main() {
  let options
  try {
    options = parseArguments(process.argv.slice(2))
  } catch (error) {
    console.error(`LAN proxy 参数错误：${error.message}`)
    process.exitCode = 2
    return
  }

  server = net.createServer({ allowHalfOpen: false }, (client) => handleClient(client, options.listenPort))
  server.once('error', (error) => {
    console.error(`LAN proxy 服务失败：${error.message}`)
    stopServer(1, server.listening ? '服务监听错误' : '')
  })

  process.once('SIGTERM', () => stopServer(0, '收到 SIGTERM'))
  process.once('SIGINT', () => stopServer(0, '收到 SIGINT'))

  server.listen(options.listenPort, options.listenHost, () => {
    console.log(`LAN proxy listening on ${options.listenHost}:${options.listenPort} -> ${UPSTREAM_HOST}:${options.listenPort}`)
  })
}

main()
