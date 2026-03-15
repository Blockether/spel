## GET http://localhost:36643/echo → 200 OK

### Request Headers
```
GET http://localhost:36643/echo
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Sun, 15 Mar 2026 14:31:41 GMT
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
curl 'http://localhost:36643/echo'
```
