const $ = id => document.getElementById(id);
let latest, catalog, runs = [];
const esc = text => String(text ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt = value => Number(value).toFixed(3);
function options() {
  const o = {query:$('query').value, mode:$('mode').value, topK:Number($('topK').value), threshold:Number($('threshold').value), diet:$('diet').value, category:$('category').value, maxMinutes:Number($('minutes').value), referenceFilters:$('referenceFilters').checked};
  if (!Number.isInteger(o.topK) || o.topK<1 || o.topK>61 || !Number.isFinite(o.threshold) || o.threshold<0 || o.threshold>1 || !Number.isInteger(o.maxMinutes) || o.maxMinutes<0) throw Error('Revisa los parámetros: Top K entre 1 y 61, umbral entre 0 y 1 y minutos positivos.');
  return o;
}
function status(message, error=false) { $('status').textContent=message; $('status').className=error?'error':''; }
async function api(path, body) {
  const response=await fetch('/api/'+path, body ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {});
  const data=await response.json(); if(!response.ok) throw Error(data.message || 'No se ha podido completar la operación.'); return data;
}
async function busy(action) {
  document.querySelectorAll('button').forEach(b=>b.disabled=true);
  try { await action(); } catch(e) { status(e.message,true); }
  finally { document.querySelectorAll('button').forEach(b=>b.disabled=false); $('export').disabled=!latest; }
}
function cards(hits) {
  $('results').innerHTML=hits.length ? hits.map(({recipe:r,score})=>`<article><span class="score">Score ${fmt(score)}</span><div class="eyebrow">${esc(r.categoria)}</div><h3>${esc(r.nombre)}</h3><p>${esc(r.descripcion)}</p><span class="tag">${esc(r.tiempo_minutos)} min</span>${r.dietas.map(d=>`<span class="tag">${esc(d)}</span>`).join('')}<details><summary>Ingredientes y preparación</summary><p>${r.ingredientes.map(i=>esc(i.nombre)).join(', ')}</p><ol>${r.instrucciones.map(i=>`<li>${esc(i)}</li>`).join('')}</ol><small>${esc(r.id)}</small></details></article>`).join('') : '<div class="empty">No hay resultados con estos parámetros. Prueba otra consulta o reduce el umbral.</div>';
}
function renderEvaluation(data) {
  const labels={precision5:'Precision@5',recall10:'Recall@10',mrr:'MRR',ndcg5:'NDCG@5'};
  $('evaluation').hidden=false;
  $('evaluation').innerHTML=`<h2>Calibración</h2><p class="hint">${esc(data.note)}</p><div class="metrics">${Object.entries(labels).map(([k,l])=>`<div class="metric">${l}<b>${fmt(data.average[k])}</b></div>`).join('')}</div><table><thead><tr><th>Consulta / resultados</th><th>P@5</th><th>R@10</th><th>MRR</th><th>NDCG@5</th></tr></thead><tbody>${data.evaluations.map(e=>`<tr><td><details><summary>${esc(e.id)} · ${esc(e.query)}</summary><ol>${e.results.map(h=>`<li>${esc(h.recipe.nombre)} · ${fmt(h.score)} · ${e.relevance.alta.includes(h.recipe.id)?'alta':e.relevance.media.includes(h.recipe.id)?'media':'sin relevancia positiva'}</li>`).join('')}</ol><p>Esperados (alta): ${e.relevance.alta.map(esc).join(', ')}<br>Esperados (media): ${e.relevance.media.map(esc).join(', ')}</p></details></td>${Object.keys(labels).map(k=>`<td>${fmt(e.metrics[k])}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
}
$('searchForm').addEventListener('submit',e=>{ e.preventDefault(); busy(async()=>{status('Buscando recetas…'); const hits=await api('search',options()); cards(hits); status(`${hits.length} recetas encontradas.`);}); });
$('preset').addEventListener('change',()=>{const q=catalog.queries.find(q=>q.id===$('preset').value); if(q) $('query').value=q.consulta;});
$('calibrate').addEventListener('click',()=>busy(async()=>{
  status('Evaluando 30 consultas… El primer uso de OpenAI genera los embeddings y puede tardar.');
  const data=await api('calibrate',options()); latest=data; renderEvaluation(data); runs.push(data);
  $('history').innerHTML='<h2>Comparación de esta sesión</h2><table><tr><th>Ejecución</th><th>Motor / K / umbral</th><th>P@5</th><th>R@10</th><th>NDCG@5</th></tr>'+runs.map((r,i)=>`<tr><td>${i+1}</td><td>${esc(r.options.mode)} / ${r.options.topK} / ${r.options.threshold}<br><small>Dieta: ${esc(r.options.diet||'todas')}; categoría: ${esc(r.options.category||'todas')}; minutos: ${r.options.maxMinutes}; filtros ref.: ${r.options.referenceFilters?'sí':'no'}</small></td><td>${fmt(r.average.precision5)}</td><td>${fmt(r.average.recall10)}</td><td>${fmt(r.average.ndcg5)}</td></tr>`).join('')+'</table>';
  status('Calibración completada. Cambia los parámetros y vuelve a evaluar para comparar.');
}));
$('export').addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([JSON.stringify({latest,runs},null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download='calibracion-recetas.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
(async()=>{try{
  catalog=await api('catalog');
  $('connection').textContent=catalog.semanticAvailable?`OpenAI configurado · ${catalog.model}`:'Modo local disponible · API key pendiente';
  if(!catalog.semanticAvailable) [...$('mode').options].filter(o=>o.value!=='lexical').forEach(o=>o.disabled=true);
  for(const [id,field] of [['diet','dietas'],['category','categoria']]) {
    const values=[...new Set(catalog.recipes.flatMap(r=>Array.isArray(r[field])?r[field]:[r[field]]))].sort();
    values.forEach(v=>$ (id).add(new Option(v,v)));
  }
  catalog.queries.forEach(q=>$('preset').add(new Option(q.consulta,q.id)));
  cards((await api('search',options()))); status('Listo para buscar y calibrar.');
}catch(e){status(e.message,true);}})();
