## DELETE http://localhost:44451/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 24 Sep 2026 23:05:04 GMT
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
curl 'http://localhost:44451/echo' \
  -X DELETE
```
