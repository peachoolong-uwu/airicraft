function query(world, input) {
  const player = world.player.position, bounds = world.metadata.bounds;
  const key = (x,y,z) => `${x},${y},${z}`;
  const cells = new Map(world.blocks.map(b => [key(b.position.x,b.position.y,b.position.z),b]));
  const counts = {};
  for (const b of world.blocks) counts[b.blockId] = (counts[b.blockId] || 0) + 1;
  const focus = (input.focus || '').split(',').map(s=>s.trim()).filter(Boolean);
  const important = /chest|barrel|furnace|crafting_table|_door$|portal|spawner|lava|_bed$|_ore$/;
  const distance = b => Math.hypot(b.position.x-player.x,b.position.y-player.y,b.position.z-player.z);
  const candidates = world.blocks.filter(b => !b.air && !b.blockId.endsWith(':water') && b.properties.half !== 'upper')
    .map(b => ({b,score:(focus.some(f=>b.blockId.includes(f))?1000:0)+(important.test(b.blockId)?100:0)+1/counts[b.blockId]}))
    .sort((a,b)=>b.score-a.score || distance(a.b)-distance(b.b) || key(a.b.position.x,a.b.position.y,a.b.position.z).localeCompare(key(b.b.position.x,b.b.position.y,b.b.position.z)));
  const limit = input.landmarkLimit === undefined ? 12 : input.landmarkLimit;
  const landmarks = candidates.slice(0,limit).map(({b},i)=> {
    const occupied=[b.position];
    if(b.blockId.endsWith('_door')) {
      const upper=cells.get(key(b.position.x,b.position.y+1,b.position.z));
      if(upper && upper.blockId===b.blockId && upper.properties.half==='upper') occupied.push(upper.position);
    }
    return {label:String(i+1),blockId:b.blockId,position:b.position,occupied,state:b.properties};
  });
  const reference = input.elevation === undefined ? player.y : input.elevation;
  const terrain=[], heights=[];
  const boxes = b => b.collisionBoxes || [];
  for(let z=bounds.min.z;z<=bounds.max.z;z++) {
    const tr=[],hr=[];
    for(let x=bounds.min.x;x<=bounds.max.x;x++) {
      let unknown=false; const surfaces=[];
      for(let y=bounds.min.y;y<=bounds.max.y;y++) {
        const b=cells.get(key(x,y,z));
        if(!b) {unknown=true;continue;}
        for(const box of boxes(b)) {
          if(box[0]>.5 || box[3]<.5 || box[2]>.5 || box[5]<.5) continue;
          const feet=y+box[4];
          // Need the entire body column captured; distinguish unavailable from blocked.
          if(feet+1.8>bounds.max.y+1) continue;
          let clear=true;
          for(let yy=Math.floor(feet);yy<feet+1.8;yy++) {
            const above=cells.get(key(x,yy,z));
            if(!above) {unknown=true;clear=false;break;}
            if(boxes(above).some(q=>q[0]<.8 && q[3]>.2 && q[2]<.8 && q[5]>.2 && yy+q[4]>feet+.001 && yy+q[1]<feet+1.8)) clear=false;
          }
          if(clear && !surfaces.some(s=>s.feet===feet)) surfaces.push({feet,b});
        }
      }
      surfaces.sort((a,b)=>Math.abs(a.feet-reference)-Math.abs(b.feet-reference) || a.feet-b.feet);
      let t='?', h='?';
      if(surfaces.length) {
        const surface=surfaces[0], delta=Math.round((surface.feet-player.y)*1000)/1000;
        h=(delta>0?'+':'')+delta;
        t=Math.abs(surface.feet-reference)<.6?'.':'^';
        const at=cells.get(key(x,Math.floor(surface.feet),z));
        if(at && at.fluid) t=at.blockId.includes('lava')?'!':'~';
        if(surfaces.length>1) t='M';
        const mark=landmarks.find(l=>l.position.x===x && l.position.z===z && l.position.y>=surface.feet-1 && l.position.y<surface.feet+2);
        if(mark && surfaces.length===1) t=mark.label;
      } else if(!unknown) {
        const body=cells.get(key(x,Math.floor(reference),z));
        t=body && !body.collisionEmpty?'#':'-'; h='-';
      }
      if(x===Math.floor(player.x) && z===Math.floor(player.z)) t='@';
      tr.push(t);hr.push(h);
    }
    terrain.push(tr);heights.push(hr);
  }
  const width=Math.max(3,...heights.flat().map(s=>s.length),...terrain.flat().map(s=>s.length));
  const grid=rows=>rows.map(row=>row.map(s=>s.padStart(width)).join(' ')).join('\n');
  return {center:player,referenceElevation:reference,bounds,orientation:'north up; columns +X east; rows +Z south; one cell per block',
    landmarks,omittedLandmarks:Math.max(0,candidates.length-limit),terrain:grid(terrain),relativeHeight:grid(heights),
    legend:'@ self; numbers landmark labels; . surface near reference elevation; ^ other elevation; # blocked column; ~ water; ! lava; M multiple candidate floors (height uses nearest reference); ? unknown; - no body-clear supporting surface in vertical bounds. Height numbers are surface offsets from player feet Y, not landmark labels.',
    caveat:'Local column geometry only, not route or full-footprint standing proof. Both maps use the same selected surface. Loaded blocks may be occluded. Landmarks can be outside the selected elevation.'};
}
