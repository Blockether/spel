## DELETE http://localhost:41975/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 29 Oct 2026 15:05:01 GMT
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
curl 'http://localhost:41975/echo' \
  -X DELETE
```
