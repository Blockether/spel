## GET http://localhost:46305/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sat, 21 Nov 2026 15:30:10 GMT
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
curl 'http://localhost:46305/echo?id=1'
```
