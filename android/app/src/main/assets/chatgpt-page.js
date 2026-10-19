// Operates visible page controls only. No fetch/XHR, Cookies, storage or internal endpoints.
function rainStudyPage(action, payload) {
    if (location.protocol !== 'https:' || location.hostname !== 'chatgpt.com') return {ok:false};
    const composer = () => document.querySelector('#prompt-textarea');
    const form = () => composer()?.closest('form');
    const send = () => document.querySelector('button[data-testid="send-button"]');
    const busy = () => !!document.querySelector('button[data-testid="stop-button"],button[data-testid="stop-streaming-button"],[data-is-streaming="true"]');
    const content = e => e ? (e.value === undefined ? e.innerText : e.value) || '' : '';
    function markdown(node) {
        if (node.nodeType === 3) return node.textContent;
        if (node.nodeType !== 1) return '';
        const tag = node.tagName.toLowerCase();
        if (['button','script','style','svg'].includes(tag) || node.getAttribute('aria-hidden') === 'true') return '';
        if (node.classList.contains('katex')) { const a = node.querySelector('annotation[encoding="application/x-tex"]'); if (a) return '$' + a.textContent + '$'; }
        if (tag === 'pre') return '\n\n```' + (node.querySelector('code')?.className.match(/language-([\w+-]+)/)?.[1] || '') + '\n' + (node.querySelector('code') || node).textContent.trim() + '\n```\n\n';
        if (tag === 'table') {
            const rows = Array.from(node.querySelectorAll('tr')).map(r => Array.from(r.querySelectorAll('th,td')).map(c => markdownChildren(c).trim().replace(/\|/g,'\\|').replace(/\n/g,'<br>')));
            if (!rows.length) return '';
            const line = r => '| ' + r.join(' | ') + ' |';
            return '\n\n' + [line(rows[0]),line(rows[0].map(()=>'---')),...rows.slice(1).map(line)].join('\n') + '\n\n';
        }
        const text = markdownChildren(node);
        if (/^h[1-6]$/.test(tag)) return '\n\n' + '#'.repeat(Number(tag[1])) + ' ' + text.trim() + '\n\n';
        if (tag === 'strong' || tag === 'b') return '**'+text+'**';
        if (tag === 'em' || tag === 'i') return '*'+text+'*';
        if (tag === 'code') return '`'+text+'`';
        if (tag === 'br') return '\n';
        if (tag === 'li') return '\n' + (node.parentElement.tagName === 'OL' ? (Array.from(node.parentElement.children).indexOf(node)+1)+'. ' : '- ') + text.trim();
        if (tag === 'ul' || tag === 'ol') return '\n'+text+'\n\n';
        if (tag === 'blockquote') return '\n\n'+text.trim().split('\n').map(l=>'> '+l).join('\n')+'\n\n';
        if (tag === 'a') { const href=node.getAttribute('href') || ''; return /^https?:\/\//.test(href) ? '['+text+']('+href+')' : text; }
        if (tag === 'p') return '\n\n'+text.trim()+'\n\n';
        return text;
    }
    function markdownChildren(node) { return Array.from(node.childNodes).map(markdown).join(''); }
    if (action === 'prepare') {
        const el = composer();
        if (!el || busy() || content(el).trim() || !payload.text || payload.text.length > 100000) return {ok:false};
        if (payload.image) {
            const match = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$/.exec(payload.image);
            const file = document.querySelector('input[type="file"][accept*="image"],input[type="file"]');
            if (!match || !file) return {ok:false};
            const raw = atob(match[2]), bytes = Uint8Array.from(raw,c=>c.charCodeAt(0)), transfer = new DataTransfer();
            transfer.items.add(new File([bytes],payload.fileName || 'rain-slide.png',{type:match[1]}));
            file.files = transfer.files; file.dispatchEvent(new Event('change',{bubbles:true}));
        }
        el.focus();
        if (el.tagName === 'TEXTAREA') {
            Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(el,payload.text);
            el.dispatchEvent(new Event('input',{bubbles:true}));
        } else { document.execCommand('insertText',false,payload.text); el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:payload.text})); }
        return {ok:content(el).includes(payload.text.slice(-80))};
    }
    if (action === 'ready') {
        const attachment = !!form()?.querySelector('button[aria-label*="Remove file"],button[aria-label*="Remove attachment"],button[aria-label*="移除附件"],button[aria-label*="删除附件"],img[alt="Uploaded image"],img[src^="blob:"]');
        return {ready:!!send() && !send().disabled && !busy() && (!payload.image || attachment) && (!payload.marker || content(composer()).includes(payload.marker)) && !form()?.querySelector('[role="progressbar"]')};
    }
    if (action === 'send') { const b=send(); if (!b || b.disabled || busy() || payload.marker && !content(composer()).includes(payload.marker)) return {sent:false}; b.click(); return {sent:true}; }
    if (action === 'answer') {
        const messages=Array.from(document.querySelectorAll('[data-message-author-role]'));
        let user=-1;
        for (let i=messages.length-1;i>=0;i--) if(messages[i].getAttribute('data-message-author-role')==='user'){user=i;break;}
        if(user<0) return {finished:false};
        if(payload.marker && !content(messages[user]).includes(payload.marker)) return {finished:false,otherUser:true};
        const assistant=messages.slice(user+1).filter(m=>m.getAttribute('data-message-author-role')==='assistant').pop();
        if(!assistant) return {finished:false};
        const article=assistant.closest('article') || assistant.parentElement;
        const done=!!article?.querySelector('[data-testid="copy-turn-action-button"],[data-testid="good-response-turn-action-button"],[data-testid="bad-response-turn-action-button"]');
        const body=assistant.querySelector('.markdown') || assistant;
        const text=markdown(body).replace(/\n{3,}/g,'\n\n').trim();
        return {matchedUser:true,finished:!busy() && done && !!text && text.length<=200000,markdown:text.slice(0,200000)};
    }
    return {ok:false};
}
