package kr.lunaf.varstore.testkit;
import java.util.*;
final class HarnessJson {
 private HarnessJson(){}
 static String json(Object value){
  if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
  if(value instanceof Map<?,?> map){List<String> items=new ArrayList<>();map.forEach((key,item)->items.add(json(key.toString())+":"+json(item)));return "{"+String.join(",",items)+"}";}
  if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(HarnessJson::json).toList())+"]";
  return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
 }
}
