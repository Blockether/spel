## GET http://localhost:45445/echo → 200 OK

### Request Headers
```
GET http://localhost:45445/echo
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Sun, 01 Mar 2026 11:11:43 GMT
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
curl 'http://localhost:45445/echo'
```
