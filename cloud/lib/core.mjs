export const HOSTS=Object.freeze({standard:'www.yuketang.cn',hetang:'pro.yuketang.cn',changjiang:'changjiang.yuketang.cn',huanghe:'huanghe.yuketang.cn'});
export function regionName(v){if(!Object.hasOwn(HOSTS,v))throw Error('请选择实际使用的雨课堂版本。');return v;}
export function numeric(v){if(typeof v!=='string'||!/^\d{1,24}$/.test(v))throw Error('资源编号必须是数字字符串。');return v;}
export function pageNumber(v,min=1,max=10000){if(!Number.isInteger(v)||v<min||v>max)throw Error('页码不在允许范围内。');return v;}
export function redact(v){const h=new Set(['sessionid','cookie','authorization','auth','token','accesstoken','refreshtoken','lessontoken','password','secret','setcookie','setauth']);if(Array.isArray(v))return v.map(redact);if(v&&typeof v==='object')return Object.fromEntries(Object.entries(v).filter(([k])=>!h.has(k.toLowerCase().replace(/[^a-z]/g,''))).map(([k,x])=>[k,redact(x)]));return v;}
export function presentationReferences(v){const ids=new Set();function walk(x,depth){if(depth>12||!x||typeof x!=='object')return;for(const[k,y]of Object.entries(x)){if(['presentation_id','presentationId'].includes(k)&&/^\d{1,24}$/.test(String(y)))ids.add(String(y));else if(typeof y==='object')walk(y,depth+1);}}walk(v,0);return [...ids];}
export function base64(b){let s='';for(let i=0;i<b.length;i+=16384)s+=String.fromCharCode(...b.subarray(i,i+16384));return btoa(s);}
const unbase64=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
async function aesKey(hex){if(typeof hex!=='string'||!/^[a-f0-9]{64}$/i.test(hex))throw Error('云端加密配置尚未完成。');return crypto.subtle.importKey('raw',Uint8Array.from(hex.match(/../g),x=>parseInt(x,16)),{name:'AES-GCM'},false,['encrypt','decrypt']);}
export async function encryptSession(s,k,owner){const iv=crypto.getRandomValues(new Uint8Array(12));const c=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:new TextEncoder().encode(owner)},await aesKey(k),new TextEncoder().encode(s));return base64(iv)+'.'+base64(new Uint8Array(c));}
export async function decryptSession(s,k,owner){const[i,c]=s.split('.');const p=await crypto.subtle.decrypt({name:'AES-GCM',iv:unbase64(i),additionalData:new TextEncoder().encode(owner)},await aesKey(k),unbase64(c));return new TextDecoder().decode(p);}
const obj=properties=>({type:'object',properties,additionalProperties:false});
export const TOOLS=[
{name:'login_start',description:'返回手机授权页链接。用同一 ChatGPT 账号打开并完成微信授权，不索取密码或 Cookie。',inputSchema:obj({})},
{name:'login_status',description:'检查当前调用者绑定的雨课堂账号。',inputSchema:obj({})},
{name:'list_courses',description:'列当前账号可读取的课程批次，不承诺包含全部历史学期。',inputSchema:obj({})},
{name:'list_course_activities',description:'分页列课程活动及明确命名的 presentation_id；不能用其他类型资源编号替代。',inputSchema:{...obj({classroom_id:{type:'string'},page:{type:'integer',minimum:0,default:0}}),required:['classroom_id']}},
{name:'list_slides',description:'列已知 presentation_id 的课件页面，page 从 1 开始。',inputSchema:{...obj({presentation_id:{type:'string'}}),required:['presentation_id']}},
{name:'read_slide',description:'读取一页已开放课件并返回图像。不支持老师当前页自动同步，缺失图表不得猜测。',inputSchema:{...obj({presentation_id:{type:'string'},page:{type:'integer',minimum:1}}),required:['presentation_id','page']}},
].map(t=>({...t,annotations:{readOnlyHint:true,destructiveHint:false,openWorldHint:true}}));

// Detect actual image bytes: WeChat QR responses may use nonstandard MIME headers.
export function imageMime(bytes){
 const starts=(values)=>values.every((v,i)=>bytes[i]===v);
 if(bytes.length>=24&&starts([137,80,78,71,13,10,26,10]))return 'image/png';
 if(bytes.length>=4&&starts([255,216,255]))return 'image/jpeg';
 const ascii=(from,to)=>String.fromCharCode(...bytes.subarray(from,to));
 if(bytes.length>=13&&['GIF87a','GIF89a'].includes(ascii(0,6)))return 'image/gif';
 if(bytes.length>=16&&ascii(0,4)==='RIFF'&&ascii(8,12)==='WEBP')return 'image/webp';
 throw Error('未返回支持的图片格式。');
}
