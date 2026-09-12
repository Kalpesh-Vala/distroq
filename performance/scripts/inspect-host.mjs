import { readFile } from 'node:fs/promises';
import { parse } from 'csv-parse/sync';
const filename=process.argv[2];
const rows=(await readFile(filename,'utf8')).trim().split(/\r?\n/).map(row=>JSON.parse(row));
const parsed=rows.filter(row=>row.raw?.startsWith('"')).map(row=>parse(row.raw)[0]);
const headers=parsed.shift();
const values=headers.map((name,index)=>({name,mean:parsed.reduce((sum,row)=>sum+(Number(row[index])||0),0)/parsed.length}));
console.log(JSON.stringify(values.filter(row=>row.name.includes('Process(')&&!row.name.includes('(Idle)')&&!row.name.includes('(_Total)')).sort((left,right)=>right.mean-left.mean).slice(0,15),null,2));