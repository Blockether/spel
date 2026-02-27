## GET http://localhost:45389/echo → 200 OK

### Request Headers
```
GET http://localhost:45389/echo
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Fri, 27 Feb 2026 11:25:50 GMT
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
curl 'http://localhost:45389/echo'
```
