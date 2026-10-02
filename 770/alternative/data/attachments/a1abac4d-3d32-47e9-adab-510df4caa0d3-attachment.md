## GET http://localhost:33953/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Fri, 02 Oct 2026 20:24:08 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1"
}
```

### cURL
```bash
curl 'http://localhost:33953/echo?id=1'
```
