## DELETE http://localhost:37741/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 30 Oct 2026 16:41:29 GMT
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
curl 'http://localhost:37741/echo' \
  -X DELETE
```
