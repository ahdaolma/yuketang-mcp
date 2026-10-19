import type {Metadata} from 'next';
import './globals.css';
export const metadata:Metadata={title:'雨课堂学习桥',description:'用手机连接课堂，让不懂的那一页更容易理解。',icons:{icon:'/favicon.svg'}};
export default function Layout({children}:{children:React.ReactNode}){return <html lang="zh-CN"><body>{children}</body></html>;}
