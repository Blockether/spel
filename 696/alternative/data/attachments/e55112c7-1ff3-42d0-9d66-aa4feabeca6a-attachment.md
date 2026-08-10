## DELETE http://localhost:33217/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 10 Aug 2026 11:16:09 GMT
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
curl 'http://localhost:33217/echo' \
  -X DELETE
```
