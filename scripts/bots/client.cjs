const mineflayer = require('mineflayer')
const readline = require('node:readline')
const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(process.env.VARSTORE_PROXY_PORT || 25580), username: process.env.VARSTORE_BOT_NAME || 'VarStoreSmoke', auth: 'offline', version: '1.21.11'})
const output = event => process.stdout.write(JSON.stringify({...event, time: Date.now()}) + '\n')
bot.on('messagestr', message => output({event: 'message', message}))
bot.on('spawn', () => output({event: 'spawn', uuid: bot.player?.uuid}))
bot.on('kicked', reason => output({event: 'kicked', reason}))
bot.on('error', error => output({event: 'error', message: error.message}))
bot.on('end', reason => output({event: 'end', reason}))
readline.createInterface({input: process.stdin}).on('line', line => {
  const request = JSON.parse(line)
  if (request.action === 'chat') { bot.chat(request.text); output({event: 'sent', id: request.id}) }
  if (request.action === 'quit') { bot.quit(); setTimeout(() => process.exit(0), 500) }
})
