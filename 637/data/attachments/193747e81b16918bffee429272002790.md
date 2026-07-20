## GET http://localhost:40791/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 20 Jul 2026 09:17:13 GMT
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
curl 'http://localhost:40791/echo?id=1'
```
