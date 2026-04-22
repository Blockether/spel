## DELETE http://localhost:41337/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 22 Apr 2026 17:22:49 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:41337/echo' \
  -X DELETE
```
