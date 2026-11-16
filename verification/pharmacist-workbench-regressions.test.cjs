const test=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs')
const vm=require('node:vm')
const path=require('node:path')
const source=fs.readFileSync(path.join(__dirname,'../admin-web/src/views/pharmacy/PharmacyWorkbenchPage.vue'),'utf8')
 .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
const defer=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
const flush=()=>new Promise(resolve=>setImmediate(resolve))
function loadPage(){
 const route={path:'/pharmacy',query:{}},watchers=[],unmount=[],gets=[],lists=[]
 const filters={status:'paid',keyword:''}
 const queuedLists=[]
 const list=()=>{lists.push(filters.status);return queuedLists.length?queuedLists.shift().promise:Promise.resolve()}
 const ref=value=>({value})
 const state={route,watchers,unmount,gets,lists,queuedLists}
 const context={ref,computed:fn=>({get value(){return fn()}}),useRoute:()=>route,useRouter:()=>({replace:()=>Promise.resolve()}),
  usePagedList:()=>({filters,rows:ref([]),total:ref(0),page:ref(1),size:ref(10),loading:ref(false),error:ref(''),load:list,search:list,reset:list}),
  useStatusRefresh:()=>{},watch:(source,callback)=>watchers.push({source,callback}),onMounted:()=>{},onBeforeUnmount:fn=>unmount.push(fn),
  parsePharmacyQuery:q=>({id:/^[1-9]\d*$/.test(String(q.id))?String(q.id):null,status:['paid','dispensed'].includes(q.status)?q.status:null,invalidId:q.id!=null&&!/^[1-9]\d*$/.test(String(q.id))}),
  api:{get:url=>{const request=defer();gets.push({url,...request});return request.promise}},auth:{permissions:{permissions:['prescription:dispense']}},ElMessage:{success(){},warning(){}},confirmAction:async()=>true,Promise,
 }
 vm.createContext(context)
 vm.runInContext(source+'\nthis.page={syncFromRoute,filters,detail,selectedId,dispensing,queryError,drawerVisible};',context)
 return {...state,...context.page}
}

test('已发药队列收到无status的处方链接时恢复待发药队列',async()=>{
 const p=loadPage();p.filters.status='dispensed';p.route.query={id:'501'}
 await p.syncFromRoute();assert.deepEqual(p.lists,['paid']);assert.equal(p.gets[0].url,'/pharmacy/prescriptions/501')
})

test('发药进行中延迟路由同步，结束后才切换队列和处方',async()=>{
 const p=loadPage();p.dispensing.value=true;p.route.query={id:'502',status:'dispensed'}
 await p.syncFromRoute();assert.equal(p.filters.status,'paid');assert.equal(p.gets.length,0)
 p.dispensing.value=false;p.watchers.find(w=>w.source===p.dispensing).callback(false)
 await flush();assert.equal(p.filters.status,'dispensed');assert.equal(p.gets[0].url,'/pharmacy/prescriptions/502')
})

test('连续路由查询的旧列表响应不能重新打开旧处方',async()=>{
 const p=loadPage(),old=defer(),latest=defer();p.queuedLists.push(old,latest)
 p.route.query={id:'501'};const first=p.syncFromRoute({initial:true})
 p.route.query={id:'502',status:'dispensed'};const second=p.syncFromRoute()
 latest.resolve();await second;p.gets[0].resolve({id:502,status:'dispensed'});await flush()
 old.resolve();await first;assert.equal(p.gets.length,1);assert.equal(p.detail.value.id,502)
})

test('等待列表期间卸载工作台，迟到响应不能再请求详情',async()=>{
 const p=loadPage(),pending=defer();p.queuedLists.push(pending);p.route.query={id:'501'}
 const loading=p.syncFromRoute({initial:true});p.unmount.forEach(fn=>fn());pending.resolve();await loading
 assert.equal(p.gets.length,0);assert.equal(p.drawerVisible.value,false)
})

test('同一处方从待发药通知切换已发药通知时立即清旧详情并重新读取',async()=>{
 const p=loadPage();p.route.query={id:'501',status:'paid'};await p.syncFromRoute()
 p.gets[0].resolve({id:501,status:'paid'});await flush();assert.equal(p.detail.value.status,'paid')
 const list=defer();p.queuedLists.push(list);p.route.query={id:'501',status:'dispensed'}
 const changing=p.syncFromRoute();assert.equal(p.detail.value,null,'新队列请求期间不能显示旧的可发药详情')
 list.resolve();await changing;assert.equal(p.gets.length,2)
 p.gets[1].resolve({id:501,status:'dispensed'});await flush();assert.equal(p.detail.value.status,'dispensed')
})

test('同一处方状态切换后的旧详情响应不能覆盖新详情',async()=>{
 const p=loadPage();p.route.query={id:'501',status:'paid'};await p.syncFromRoute()
 p.route.query={id:'501',status:'dispensed'};await p.syncFromRoute();assert.equal(p.gets.length,2)
 p.gets[1].resolve({id:501,status:'dispensed'});await flush()
 p.gets[0].resolve({id:501,status:'paid'});await flush();assert.equal(p.detail.value.status,'dispensed')
})
