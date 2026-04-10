## GET http://localhost:40219/echo?id=1&fields=name%2Cemail → 200 OK

### Request Headers
```
GET http://localhost:40219/echo?id=1&fields=name%2Cemail
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Fri, 10 Apr 2026 22:28:59 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1&fields=name,email"
}
```

### cURL
```bash
curl 'http://localhost:40219/echo?id=1&fields=name%2Cemail'
```
