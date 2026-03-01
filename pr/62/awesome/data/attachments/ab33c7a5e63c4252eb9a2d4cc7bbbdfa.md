## GET http://localhost:44349/echo → 200 OK

### Request Headers
```
GET http://localhost:44349/echo
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Sun, 01 Mar 2026 14:52:57 GMT
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
curl 'http://localhost:44349/echo'
```
