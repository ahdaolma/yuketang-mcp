// Operates visible page controls only. No fetch/XHR, Cookies, storage or internal endpoints.
function rainStudyPage(action, payload) {
    if (location.protocol !== 'https:' || location.hostname !== 'chatgpt.com') return {ok:false};
    const composer = () => document.querySelector('#prompt-textarea');
    const form = () => composer()?.closest('form');
    const visible = e => !!e && !!e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden';
    const send = () => Array.from(document.querySelectorAll('button[data-testid="send-button"],button[data-testid="fruitjuice-send-button"],button[aria-label="Send prompt"],button[aria-label="发送提示"]')).find(visible);
    const busy = () => Array.from(document.querySelectorAll('button[data-testid="stop-button"],button[data-testid="stop-streaming-button"],[data-is-streaming="true"],[data-message-state="streaming"],button[aria-label="Stop generating"],button[aria-label="停止生成"]')).some(visible);
    const content = e => e ? (e.value === undefined ? e.innerText : e.value) || '' : '';
    const hasAttachment = () => !!form()?.querySelector('button[aria-label*="Remove file"],button[aria-label*="Remove attachment"],button[aria-label*="移除附件"],button[aria-label*="删除附件"],img[alt="Uploaded image"],img[src^="blob:"]') || Array.from(document.querySelectorAll('input[type="file"]')).some(e => e.files?.length);
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
    if (action === 'outline' || action === 'navigate') {
        const role = node => node.getAttribute('data-message-author-role') || node.getAttribute('data-turn');
        const messages = Array.from(document.querySelectorAll('[data-message-author-role],[data-turn="user"],[data-turn="assistant"]'))
            .filter(node => visible(node) && ['user','assistant'].includes(role(node)) && (node.hasAttribute('data-message-author-role') || !node.querySelector('[data-message-author-role]')));
        if (action === 'outline') return {ok:true, labels:messages.map(node => (role(node)==='user'?'你：':'回答：') + content(node).trim().slice(0,100))};
        let target;
        if (payload.target === 'top') target = messages[0];
        else if (payload.target === 'bottom') target = messages[messages.length-1];
        else if (Number.isInteger(payload.index)) target = messages[payload.index];
        if (!target || payload.expected && payload.expected !== (role(target)==='user'?'你：':'回答：') + content(target).trim().slice(0,100)) return {ok:false};
        target.scrollIntoView({block:payload.target === 'bottom'?'end':'start',behavior:'instant'});
        return {ok:true};
    }
    if (action === 'prepare') {
        const el = composer();
        if (!el || busy() || content(el).trim() || hasAttachment() || !payload.text || payload.text.length > 100000) return {ok:false};
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
        // Rich editors normalize line breaks and nonbreaking spaces while inserting plain text.
        // Compare the complete content without requiring identical DOM whitespace.
        const normalize = text => text.replace(/\s+/g,' ').trim();
        return {ok:normalize(content(el)) === normalize(payload.text)};
    }
    if (action === 'ready') {
        const attachment = !!form()?.querySelector('button[aria-label*="Remove file"],button[aria-label*="Remove attachment"],button[aria-label*="移除附件"],button[aria-label*="删除附件"],img[alt="Uploaded image"],img[src^="blob:"]');
        return {ready:!!send() && !send().disabled && !busy() && (!payload.image || attachment) && (!payload.marker || content(composer()).includes(payload.marker)) && !form()?.querySelector('[role="progressbar"]')};
    }
    if (action === 'send') { const b=send(); if (!b || b.disabled || busy() || payload.marker && !content(composer()).includes(payload.marker)) return {sent:false}; b.click(); return {sent:true}; }
    if (action === 'answer') {
        // Current and older page layouts use different turn containers. Prefer the
        // message node when both exist, so each turn is counted exactly once.
        const role = node => node.getAttribute('data-message-author-role') || node.getAttribute('data-turn');
        const messages=Array.from(document.querySelectorAll('[data-message-author-role],[data-turn="user"],[data-turn="assistant"]'))
            .filter(node => ['user','assistant'].includes(role(node)) && (node.hasAttribute('data-message-author-role') || !node.querySelector('[data-message-author-role]')));
        let user=-1;
        for (let i=messages.length-1;i>=0;i--) if(role(messages[i])==='user'){user=i;break;}
        if(user<0) return {finished:false,phase:'missing-user'};
        if(payload.marker && !content(messages[user]).includes(payload.marker)) return {finished:false,otherUser:true,phase:'wrong-conversation'};
        const assistant=messages.slice(user+1).filter(m=>role(m)==='assistant').pop();
        if(!assistant) return {finished:false,matchedUser:true,phase:'waiting'};
        const article=assistant.closest('[data-testid^="conversation-turn-"],[data-turn="assistant"],article') || assistant.parentElement;
        const done=Array.from(article?.querySelectorAll('button') || []).some(button => {
            if (!visible(button) || button.closest(".markdown,pre,code")) return false;
            const testId=button.getAttribute('data-testid') || '';
            const label=button.getAttribute('aria-label') || button.getAttribute('title') || '';
            return /^(copy-turn-action-button|good-response-turn-action-button|bad-response-turn-action-button)$/.test(testId)
                || /^(Copy|Copy response|Copy answer|Good response|Bad response|复制|复制回答|复制回复|好评|差评|赞|踩)$/i.test(label.trim());
        });
        const body=assistant.querySelector('.markdown') || assistant;
        const text=markdown(body).replace(/\n{3,}/g,'\n\n').trim();
        const streaming=busy();
        return {matchedUser:true,finished:!streaming && done && !!text && text.length<=200000,
            phase:streaming?'generating':done?'complete':'unconfirmed',markdown:text.slice(0,200000)};
    }
    return {ok:false};
}
