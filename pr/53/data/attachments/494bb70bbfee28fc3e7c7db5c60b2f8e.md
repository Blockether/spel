## GET http://localhost:46655/echo → 200 OK

### Request Headers
```
GET http://localhost:46655/echo
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Sat, 28 Feb 2026 09:04:09 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "name=Alice&role=Engineer&team=Platform"
}
```

### cURL
```bash
curl 'http://localhost:46655/echo'
```
