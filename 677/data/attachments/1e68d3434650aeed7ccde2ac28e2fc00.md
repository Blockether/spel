## DELETE http://localhost:33295/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 01 Aug 2026 19:48:39 GMT
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
curl 'http://localhost:33295/echo' \
  -X DELETE
```
